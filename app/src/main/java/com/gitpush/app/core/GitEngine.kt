package com.gitpush.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EngineException(val code: String, message: String) : Exception(message)

@Serializable
data class EngineRequest(
    val owner: String,
    val repo: String,
    val branch: String,
    @SerialName("base_branch") val baseBranch: String? = null,
    val token: String,
    @SerialName("stage_dir") val stageDir: String,
    @SerialName("target_subdir") val targetSubdir: String = "",
    @SerialName("respect_gitignore") val respectGitignore: Boolean = true,
    @SerialName("extra_ignores") val extraIgnores: List<String> = emptyList(),
    @SerialName("max_file_bytes") val maxFileBytes: Long,
    @SerialName("delete_missing") val deleteMissing: Boolean = false,
    val message: String = "",
    @SerialName("author_name") val authorName: String = "",
    @SerialName("author_email") val authorEmail: String = "",
    @SerialName("tz_offset") val tzOffset: String = "+0000",
    val timestamp: Long = 0,
    val threads: Int = 4,
    @SerialName("temp_dir") val tempDir: String = "",
)

@Serializable
data class FileChange(val path: String, val size: Long)

@Serializable
data class SkippedFile(val path: String, val reason: String)

@Serializable
data class PlanData(
    val added: List<FileChange> = emptyList(),
    val modified: List<FileChange> = emptyList(),
    val deleted: List<FileChange> = emptyList(),
    @SerialName("added_count") val addedCount: Int = 0,
    @SerialName("modified_count") val modifiedCount: Int = 0,
    @SerialName("deleted_count") val deletedCount: Int = 0,
    @SerialName("unchanged_count") val unchangedCount: Int = 0,
    val skipped: List<SkippedFile> = emptyList(),
    @SerialName("skipped_count") val skippedCount: Int = 0,
    @SerialName("upload_bytes") val uploadBytes: Long = 0,
    @SerialName("local_files") val localFiles: Int = 0,
    @SerialName("local_bytes") val localBytes: Long = 0,
    @SerialName("parent_sha") val parentSha: String? = null,
    @SerialName("branch_exists") val branchExists: Boolean = false,
    @SerialName("repo_empty") val repoEmpty: Boolean = false,
) {
    val changeCount: Int get() = addedCount + modifiedCount + deletedCount
}

@Serializable
data class PushData(
    val status: String,
    @SerialName("commit_sha") val commitSha: String? = null,
    val branch: String,
    val added: Int = 0,
    val modified: Int = 0,
    val deleted: Int = 0,
    @SerialName("bytes_sent") val bytesSent: Long = 0,
    @SerialName("remote_messages") val remoteMessages: List<String> = emptyList(),
) {
    val upToDate: Boolean get() = status == "up_to_date"
}

@Serializable
data class ExtractData(
    val files: Long = 0,
    val bytes: Long = 0,
    @SerialName("stripped_root") val strippedRoot: String? = null,
    val skipped: List<SkippedFile> = emptyList(),
)

/** Serialises native calls (the engine has one global cancel flag) and decodes the JSON envelope. */
class GitEngine(private val json: Json) {
    private val mutex = Mutex()

    val version: String by lazy { runCatching { NativeBridge.nativeVersion() }.getOrDefault("unavailable") }

    fun cancel() {
        runCatching { NativeBridge.nativeCancel() }
    }

    suspend fun plan(req: EngineRequest, listener: ProgressListener): PlanData =
        call(PlanData.serializer()) { NativeBridge.nativePlan(json.encodeToString(EngineRequest.serializer(), req), listener) }

    suspend fun push(req: EngineRequest, listener: ProgressListener): PushData =
        call(PushData.serializer()) { NativeBridge.nativePush(json.encodeToString(EngineRequest.serializer(), req), listener) }

    /** [fd] must be a detached descriptor; the engine closes it. */
    suspend fun extractZip(
        fd: Int,
        destDir: String,
        stripRoot: Boolean,
        maxFileBytes: Long,
        listener: ProgressListener,
    ): ExtractData = call(ExtractData.serializer()) { NativeBridge.nativeExtractZip(fd, destDir, stripRoot, maxFileBytes, listener) }

    private suspend fun <T> call(ser: KSerializer<T>, block: () -> String): T =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val raw = try {
                    block()
                } catch (e: UnsatisfiedLinkError) {
                    throw EngineException("ENGINE_MISSING", "The native engine could not be loaded: ${e.message}")
                }
                decode(raw, ser)
            }
        }

    private fun <T> decode(raw: String, ser: KSerializer<T>): T {
        val root = json.parseToJsonElement(raw).jsonObject
        if (root["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            return json.decodeFromJsonElement(ser, root.getValue("data"))
        }
        val err = root["error"]?.jsonObject
        throw EngineException(
            err?.get("code")?.jsonPrimitive?.contentOrNull ?: "UNKNOWN",
            err?.get("message")?.jsonPrimitive?.contentOrNull ?: "Unknown error",
        )
    }
}
