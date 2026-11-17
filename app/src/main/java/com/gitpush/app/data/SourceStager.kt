package com.gitpush.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.gitpush.app.core.GitEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

enum class SourceKind { FOLDER, ZIP }

data class SourceRef(val kind: SourceKind, val uri: Uri, val label: String)

/**
 * Android hands us content:// URIs, but the Rust engine needs real paths.
 * The stager mirrors the picked folder (or unpacks the ZIP) into app storage.
 * Folder mirrors are incremental: unchanged files (same size + mtime) are not copied again.
 */
class SourceStager(private val context: Context, private val engine: GitEngine) {

    private val root: File get() = File(context.filesDir, "stage")

    fun stageDir(source: SourceRef): File = File(root, key(source.uri.toString()))

    fun tempDir(): File = File(context.cacheDir, "push").apply { mkdirs() }

    private fun key(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    fun cacheSizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clearCache() {
        root.deleteRecursively()
    }

    fun clearStage(source: SourceRef) {
        stageDir(source).deleteRecursively()
    }

    fun displayName(uri: Uri, tree: Boolean): String {
        if (tree) return DocumentFile.fromTreeUri(context, uri)?.name ?: "Folder"
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "archive.zip"
    }

    suspend fun stage(
        source: SourceRef,
        settings: AppSettings,
        onProgress: (done: Long, total: Long, note: String) -> Unit,
    ) {
        val dest = stageDir(source)
        when (source.kind) {
            SourceKind.FOLDER -> stageFolder(source.uri, dest, settings.excludedDirSet, onProgress)
            SourceKind.ZIP -> stageZip(source.uri, dest, settings, onProgress)
        }
    }

    // ---- ZIP -------------------------------------------------------------------------------

    private suspend fun stageZip(uri: Uri, dest: File, s: AppSettings, onProgress: (Long, Long, String) -> Unit) {
        withContext(Dispatchers.IO) {
            dest.deleteRecursively()
            dest.mkdirs()
        }
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Cannot open the ZIP file")
        val fd = pfd.detachFd() // ownership moves to the Rust engine, which closes it
        engine.extractZip(fd, dest.absolutePath, s.stripZipRoot, s.maxFileMb * 1024L * 1024L) { _, done, total, msg ->
            onProgress(done, total, msg)
        }
    }

    // ---- Folder ----------------------------------------------------------------------------

    private class CopyTask(val uri: Uri, val dest: File, val size: Long, val modified: Long)

    private suspend fun stageFolder(
        tree: Uri,
        dest: File,
        excluded: Set<String>,
        onProgress: (Long, Long, String) -> Unit,
    ) = coroutineScope {
        val resolver = context.contentResolver
        val tasks = ArrayList<CopyTask>()
        withContext(Dispatchers.IO) {
            dest.mkdirs()
            val stack = ArrayDeque<Pair<String, File>>()
            stack.addLast(DocumentsContract.getTreeDocumentId(tree) to dest)
            var dirs = 0L
            while (stack.isNotEmpty()) {
                ensureActive()
                val (docId, dir) = stack.removeLast()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                val seen = HashSet<String>()
                resolver.query(children, PROJECTION, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val childId = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (!safeName(name) || name in excluded) continue
                        val mime = c.getString(2)
                        val size = if (c.isNull(3)) -1L else c.getLong(3)
                        val modified = if (c.isNull(4)) 0L else c.getLong(4)
                        val flags = c.getInt(5)
                        if (mime == Document.MIME_TYPE_DIR) {
                            val d = File(dir, name)
                            if (d.exists() && !d.isDirectory) d.delete()
                            d.mkdirs()
                            seen += name
                            stack.addLast(childId to d)
                        } else {
                            if (flags and Document.FLAG_VIRTUAL_DOCUMENT != 0) continue
                            val f = File(dir, name)
                            if (f.isDirectory) f.deleteRecursively()
                            seen += name
                            tasks += CopyTask(DocumentsContract.buildDocumentUriUsingTree(tree, childId), f, size, modified)
                        }
                    }
                }
                // Remove files that no longer exist in the source.
                dir.listFiles()?.forEach { if (it.name !in seen) it.deleteRecursively() }
                dirs++
                if (dirs % 20L == 0L) onProgress(0, 0, "Reading folders… ${tasks.size} files")
            }
        }

        val total = tasks.size.toLong()
        val done = AtomicInteger(0)
        val gate = Semaphore(4)
        onProgress(0, total, "")
        tasks.map { t ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    copyIfChanged(t)
                    onProgress(done.incrementAndGet().toLong(), total, t.dest.name)
                }
            }
        }.awaitAll()
        Unit
    }

    private fun copyIfChanged(t: CopyTask) {
        if (t.dest.isFile && t.size >= 0 && t.modified > 0 && t.dest.length() == t.size && t.dest.lastModified() == t.modified) {
            return
        }
        t.dest.parentFile?.mkdirs()
        val tmp = File(t.dest.path + ".gptmp")
        try {
            val input = context.contentResolver.openInputStream(t.uri) ?: throw IOException("Cannot open ${t.dest.name}")
            input.use { src -> tmp.outputStream().buffered(BUF).use { out -> src.copyTo(out, BUF) } }
            if (!tmp.renameTo(t.dest)) {
                t.dest.delete()
                if (!tmp.renameTo(t.dest)) throw IOException("Cannot write ${t.dest.name}")
            }
            if (t.modified > 0) t.dest.setLastModified(t.modified)
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun safeName(name: String) =
        name.isNotEmpty() && name != "." && name != ".." && !name.contains('/') && !name.contains('\u0000')

    private companion object {
        const val BUF = 256 * 1024
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
