package com.gitpush.app.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.gitpush.app.BuildConfig
import com.gitpush.app.data.GhAsset
import com.gitpush.app.data.GhRelease
import com.gitpush.app.data.GitHubApi
import com.gitpush.app.data.GitHubException
import com.gitpush.app.data.SettingKeys
import com.gitpush.app.data.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class UpdateInfo(
    val repo: String,
    val version: String,
    val notes: String,
    val assetId: Long,
    val assetName: String,
    val size: Long,
    val checksumAssetId: Long?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object NotConfigured : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * In-app updater. Follows the GitHub Releases of one repository ("owner/repo"), picks the APK
 * that matches this phone's CPU, checks its SHA-256 against SHA256SUMS.txt, then hands it to
 * the system installer. Works for private repositories too because it reuses the signed-in token.
 */
class UpdateManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    @Suppress("unused") private val json: Json,
    private val api: GitHubApi,
    private val settings: SettingsRepository,
    private val tokenProvider: () -> String?,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    val currentVersion: String = BuildConfig.VERSION_NAME.substringBefore('-')

    private val updatesDir: File get() = File(app.cacheDir, "updates")

    /** Called on start-up: whatever was downloaded last time has either been installed or is stale. */
    fun cleanup() {
        updatesDir.deleteRecursively()
    }

    suspend fun check(manual: Boolean) {
        when (_state.value) {
            is UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Ready -> return
            else -> Unit
        }
        val s = settings.flow.first()
        val repo = s.updateRepo.trim()
        if (!REPO_RE.matches(repo)) {
            if (manual) _state.value = UpdateState.NotConfigured
            return
        }
        val now = System.currentTimeMillis()
        if (!manual && now - s.lastUpdateCheck < AUTO_CHECK_INTERVAL_MS) return

        _state.value = UpdateState.Checking
        try {
            val release = api.latestRelease(repo)
            settings.set(SettingKeys.lastUpdateCheck, now)
            val remote = release.tagName.trim().trimStart('v', 'V')
            if (!isNewer(remote, currentVersion)) {
                _state.value = UpdateState.UpToDate(currentVersion)
                return
            }
            val asset = pickAsset(release)
            if (asset == null) {
                _state.value = UpdateState.Failed("Version $remote has no APK for this phone (${Build.SUPPORTED_ABIS.firstOrNull()}).")
                return
            }
            _state.value = UpdateState.Available(
                UpdateInfo(
                    repo = repo,
                    version = remote,
                    notes = release.body.orEmpty().trim(),
                    assetId = asset.id,
                    assetName = asset.name,
                    size = asset.size,
                    checksumAssetId = release.assets.firstOrNull { it.name == CHECKSUM_FILE }?.id,
                ),
            )
        } catch (e: CancellationException) {
            _state.value = UpdateState.Idle
            throw e
        } catch (e: GitHubException) {
            _state.value = if (manual) {
                UpdateState.Failed(if (e.status == 404) "No release has been published in $repo yet." else (e.message ?: "Update check failed."))
            } else UpdateState.Idle
        } catch (e: Exception) {
            _state.value = if (manual) UpdateState.Failed("Could not reach GitHub.") else UpdateState.Idle
        }
    }

    /** Prefers the build made for this phone's own ABI, falls back to the universal APK. */
    private fun pickAsset(release: GhRelease): GhAsset? {
        for (abi in Build.SUPPORTED_ABIS) {
            release.assets.firstOrNull { it.name.endsWith("-$abi.apk") }?.let { return it }
        }
        return release.assets.firstOrNull { it.name.endsWith("-universal.apk") }
    }

    fun download() {
        val info = (_state.value as? UpdateState.Available)?.info ?: return
        job?.cancel()
        _state.value = UpdateState.Downloading(info, 0, info.size)
        job = scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { fetchApk(info) }
                _state.value = UpdateState.Ready(info, file)
            } catch (e: CancellationException) {
                updatesDir.deleteRecursively()
                _state.value = UpdateState.Available(info)
                throw e
            } catch (e: Exception) {
                updatesDir.deleteRecursively()
                _state.value = UpdateState.Failed(e.message ?: "Download failed.")
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
    }

    /** Returns false when Android first needs the user to allow installs from GitPush. */
    fun install(context: Context): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return true
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", ready.file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    // ---- download ------------------------------------------------------------------------

    private fun assetRequest(repo: String, id: Long): Request =
        Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/assets/$id")
            .header("Accept", "application/octet-stream")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { tokenProvider()?.let { header("Authorization", "Bearer $it") } }
            .build()

    private suspend fun fetchApk(info: UpdateInfo): File {
        val dir = updatesDir.apply { deleteRecursively(); mkdirs() }
        val out = File(dir, info.assetName)

        // Expected hash (optional: older releases might not have the file).
        val expected: String? = info.checksumAssetId?.let { id ->
            runCatching {
                client.newCall(assetRequest(info.repo, id)).execute().use { it.body?.string().orEmpty() }
            }.getOrNull()?.let { parseChecksum(it, info.assetName) }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(assetRequest(info.repo, info.assetId)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code}).")
            val body = resp.body ?: throw IOException("The download was empty.")
            val total = if (body.contentLength() > 0) body.contentLength() else info.size
            body.byteStream().use { input ->
                out.outputStream().buffered(BUF).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastEmit = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done - lastEmit >= 128 * 1024) {
                            lastEmit = done
                            _state.value = UpdateState.Downloading(info, done, total)
                        }
                    }
                }
            }
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expected != null && !expected.equals(actual, ignoreCase = true)) {
            throw IOException("The download is corrupted (checksum mismatch). Try again.")
        }
        verifyApk(out)
        return out
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File) {
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(file.absolutePath, 0)
            ?: throw IOException("The downloaded file is not a valid APK.")
        if (archive.packageName != app.packageName) {
            throw IOException("This update is for a different app (${archive.packageName}). A debug build cannot update to a release build.")
        }
        val installed = pm.getPackageInfo(app.packageName, 0)
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw IOException("The downloaded version is not newer than the installed one.")
        }
    }

    private fun parseChecksum(text: String, name: String): String? =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.endsWith(name) }
            ?.substringBefore(' ')
            ?.takeIf { it.length == 64 }

    companion object {
        const val CHECKSUM_FILE = "SHA256SUMS.txt"
        private const val BUF = 256 * 1024
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private val REPO_RE = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")

        /** Compares dotted versions numerically ("1.10.0" > "1.9.3"). */
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(v: String) =
                v.trim().trimStart('v', 'V').substringBefore('-').substringBefore('+')
                    .split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(remote)
            val b = parts(local)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
