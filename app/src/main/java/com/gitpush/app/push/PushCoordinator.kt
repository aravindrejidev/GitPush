package com.gitpush.app.push

import android.app.Application
import android.os.SystemClock
import com.gitpush.app.core.EngineException
import com.gitpush.app.core.EngineRequest
import com.gitpush.app.core.GitEngine
import com.gitpush.app.core.PlanData
import com.gitpush.app.core.ProgressListener
import com.gitpush.app.core.PushData
import com.gitpush.app.data.AppDatabase
import com.gitpush.app.data.AppSettings
import com.gitpush.app.data.AuthRepository
import com.gitpush.app.data.ProjectEntity
import com.gitpush.app.data.PushEntity
import com.gitpush.app.data.SettingsRepository
import com.gitpush.app.data.SourceKind
import com.gitpush.app.data.SourceRef
import com.gitpush.app.data.SourceStager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

data class PushSpec(
    val source: SourceRef,
    val owner: String,
    val repo: String,
    val branch: String,
    val baseBranch: String,
    val message: String,
    val subdir: String,
    val deleteMissing: Boolean,
    val remember: Boolean,
) {
    val fullName: String get() = "$owner/$repo"
}

enum class Phase(val key: String, val label: String, val bytes: Boolean) {
    STAGE("stage", "Copying files", false),
    EXTRACT("extract", "Extracting ZIP", true),
    SCAN("scan", "Scanning files", false),
    HASH("hash", "Fingerprinting", true),
    REMOTE("remote", "Reading repository", false),
    DIFF("diff", "Comparing changes", false),
    PACK("pack", "Packing", true),
    UPLOAD("upload", "Uploading", true),
    DONE("done", "Finishing", false);

    companion object {
        fun from(key: String): Phase = entries.firstOrNull { it.key == key } ?: STAGE
    }
}

sealed interface PushState {
    data object Idle : PushState
    data class Working(
        val push: Boolean,
        val phase: Phase,
        val done: Long,
        val total: Long,
        val note: String,
        val repo: String,
    ) : PushState

    data class Planned(val spec: PushSpec, val plan: PlanData) : PushState
    data class Finished(val spec: PushSpec, val result: PushData, val durationMs: Long) : PushState
    data class Failed(val spec: PushSpec?, val code: String, val message: String) : PushState
}

/**
 * Runs staging + the native engine on an app-scoped coroutine so work survives screen changes.
 * A foreground service ([PushService]) keeps the process alive and shows progress.
 */
class PushCoordinator(
    private val app: Application,
    private val scope: CoroutineScope,
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
    private val stager: SourceStager,
    private val engine: GitEngine,
    private val db: AppDatabase,
) {
    private val _state = MutableStateFlow<PushState>(PushState.Idle)
    val state: StateFlow<PushState> = _state.asStateFlow()
    private var job: Job? = null

    val busy: Boolean get() = _state.value is PushState.Working

    /** Dry run: stage the source and compare it with the repository. */
    fun review(spec: PushSpec) = start(spec, push = false, reuseStage = false)

    /** Pushes. [fromReview] reuses the staged copy so exactly what was reviewed is sent. */
    fun push(spec: PushSpec, fromReview: Boolean) = start(spec, push = true, reuseStage = fromReview)

    fun cancel() {
        engine.cancel()
        job?.cancel()
    }

    fun reset() {
        if (!busy) _state.value = PushState.Idle
    }

    private fun start(spec: PushSpec, push: Boolean, reuseStage: Boolean) {
        if (busy) return
        _state.value = PushState.Working(push, Phase.STAGE, 0, 0, "", spec.fullName)
        PushService.start(app)
        job = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            try {
                val s = settings.flow.first()
                val token = auth.currentToken() ?: throw EngineException("AUTH_REQUIRED", "You are not signed in.")
                if (!reuseStage) {
                    val phase = if (spec.source.kind == SourceKind.ZIP) Phase.EXTRACT else Phase.STAGE
                    stager.stage(spec.source, s) { done, total, note -> setWorking(push, phase, done, total, note, spec) }
                }
                val req = buildRequest(spec, s, token, push)
                val listener = ProgressListener { phase, done, total, msg ->
                    setWorking(push, Phase.from(phase), done, total, msg, spec)
                }
                if (!push) {
                    val plan = engine.plan(req, listener)
                    _state.value = PushState.Planned(spec, plan)
                } else {
                    val result = engine.push(req, listener)
                    val took = SystemClock.elapsedRealtime() - t0
                    record(spec, result, null, took)
                    if (!s.keepStaged) stager.clearStage(spec.source)
                    _state.value = PushState.Finished(spec, result, took)
                }
            } catch (e: CancellationException) {
                _state.value = PushState.Idle
            } catch (e: EngineException) {
                if (e.code == "CANCELLED") {
                    _state.value = PushState.Idle
                } else {
                    if (push) record(spec, null, e.message, SystemClock.elapsedRealtime() - t0)
                    _state.value = PushState.Failed(spec, e.code, e.message ?: "Push failed")
                }
            } catch (e: Throwable) {
                if (push) record(spec, null, e.message, SystemClock.elapsedRealtime() - t0)
                _state.value = PushState.Failed(spec, "ERROR", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun setWorking(push: Boolean, phase: Phase, done: Long, total: Long, note: String, spec: PushSpec) {
        _state.value = PushState.Working(push, phase, done, total, note, spec.fullName)
    }

    private fun buildRequest(spec: PushSpec, s: AppSettings, token: String, push: Boolean): EngineRequest {
        val now = System.currentTimeMillis()
        val offset = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds
        val tz = String.format(
            Locale.US, "%s%02d%02d",
            if (offset < 0) "-" else "+", abs(offset) / 3600, abs(offset) % 3600 / 60,
        )
        val user = auth.currentUser()
        val name = s.authorName.ifBlank { user?.name?.takeIf { it.isNotBlank() } ?: user?.login ?: "GitPush" }
        val email = s.authorEmail.ifBlank {
            user?.let { "${it.id}+${it.login}@users.noreply.github.com" } ?: "gitpush@users.noreply.github.com"
        }
        val threads = if (s.threads > 0) s.threads else Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        return EngineRequest(
            owner = spec.owner,
            repo = spec.repo,
            branch = spec.branch,
            baseBranch = spec.baseBranch.ifBlank { null },
            token = token,
            stageDir = stager.stageDir(spec.source).absolutePath,
            targetSubdir = spec.subdir,
            respectGitignore = s.respectGitignore,
            extraIgnores = s.extraIgnoreLines,
            maxFileBytes = s.maxFileMb * 1024L * 1024L,
            deleteMissing = spec.deleteMissing,
            message = if (push) spec.message.ifBlank { s.defaultMessage } else "",
            authorName = name,
            authorEmail = email,
            tzOffset = tz,
            timestamp = now / 1000,
            threads = threads,
            tempDir = stager.tempDir().absolutePath,
        )
    }

    private suspend fun record(spec: PushSpec, result: PushData?, error: String?, took: Long) {
        val now = System.currentTimeMillis()
        db.pushes().insert(
            PushEntity(
                repoFullName = spec.fullName,
                branch = spec.branch,
                commitSha = result?.commitSha,
                message = spec.message.ifBlank { "Update via GitPush" },
                status = when {
                    result == null -> "failed"
                    result.upToDate -> "up_to_date"
                    else -> "pushed"
                },
                added = result?.added ?: 0,
                modified = result?.modified ?: 0,
                deleted = result?.deleted ?: 0,
                bytes = result?.bytesSent ?: 0,
                durationMs = took,
                error = error,
                createdAt = now,
            ),
        )
        if (result == null || !spec.remember) return
        val dao = db.projects()
        val existing = dao.find(spec.source.uri.toString(), spec.owner, spec.repo, spec.branch)
        if (existing != null) {
            dao.update(
                existing.copy(
                    lastMessage = spec.message,
                    lastPushAt = if (result.upToDate) existing.lastPushAt else now,
                    lastCommitSha = result.commitSha ?: existing.lastCommitSha,
                    targetSubdir = spec.subdir,
                    deleteMissing = spec.deleteMissing,
                    baseBranch = spec.baseBranch,
                ),
            )
        } else {
            dao.insert(
                ProjectEntity(
                    name = spec.source.label,
                    sourceKind = spec.source.kind.name,
                    sourceUri = spec.source.uri.toString(),
                    sourceLabel = spec.source.label,
                    owner = spec.owner,
                    repo = spec.repo,
                    branch = spec.branch,
                    baseBranch = spec.baseBranch,
                    targetSubdir = spec.subdir,
                    deleteMissing = spec.deleteMissing,
                    lastMessage = spec.message,
                    lastPushAt = if (result.upToDate) null else now,
                    lastCommitSha = result.commitSha,
                    createdAt = now,
                ),
            )
        }
    }
}
