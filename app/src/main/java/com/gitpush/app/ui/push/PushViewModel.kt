package com.gitpush.app.ui.push

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gitpush.app.AppContainer
import com.gitpush.app.data.GhRepo
import com.gitpush.app.data.SourceKind
import com.gitpush.app.data.SourceRef
import com.gitpush.app.push.PushSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RepoRef(val owner: String, val name: String, val defaultBranch: String, val isPrivate: Boolean) {
    val fullName: String get() = "$owner/$name"
}

fun isValidBranchName(b: String): Boolean =
    b.isNotEmpty() &&
        Regex("^[A-Za-z0-9][A-Za-z0-9._/-]*$").matches(b) &&
        !b.contains("..") && !b.contains("//") && !b.endsWith("/") && !b.endsWith(".") && !b.endsWith(".lock")

data class PushForm(
    val source: SourceRef? = null,
    val repo: RepoRef? = null,
    val branches: List<String> = emptyList(),
    val branchesLoading: Boolean = false,
    val branch: String = "",
    val newBranch: Boolean = false,
    val message: String = "",
    val subdir: String = "",
    val deleteMissing: Boolean = false,
    val remember: Boolean = true,
    val error: String? = null,
) {
    val subdirClean: String get() = subdir.trim().trim('/')
    val subdirValid: Boolean get() = !subdirClean.split('/').any { it == ".." || it.equals(".git", true) }
    val branchValid: Boolean get() = if (newBranch) isValidBranchName(branch) else branch.isNotEmpty()
    val ready: Boolean get() = source != null && repo != null && !branchesLoading && branchValid && subdirValid

    fun toSpec(): PushSpec? {
        val s = source ?: return null
        val r = repo ?: return null
        if (!ready) return null
        return PushSpec(
            source = s,
            owner = r.owner,
            repo = r.name,
            branch = branch,
            baseBranch = if (newBranch && branches.isNotEmpty()) r.defaultBranch else "",
            message = message.trim(),
            subdir = subdirClean,
            deleteMissing = deleteMissing,
            remember = remember,
        )
    }
}

class PushViewModel(
    private val c: AppContainer,
    private val projectId: Long,
    presetOwner: String?,
    presetRepo: String?,
) : ViewModel() {
    val form = MutableStateFlow(PushForm())
    val repos = MutableStateFlow<List<GhRepo>>(emptyList())
    val reposLoading = MutableStateFlow(false)

    init {
        c.coordinator.reset() // forget a finished run from an earlier visit
        viewModelScope.launch {
            val s = c.settings.flow.first()
            form.update { it.copy(message = s.defaultMessage) }
            when {
                projectId > 0 -> loadProject(projectId)
                presetOwner != null && presetRepo != null -> loadPreset(presetOwner, presetRepo)
            }
        }
        viewModelScope.launch {
            c.sharedZip.filterNotNull().collect { uri ->
                c.sharedZip.value = null
                setSource(SourceKind.ZIP, uri)
            }
        }
    }

    // ---- source -------------------------------------------------------------------------

    fun setSource(kind: SourceKind, uri: Uri) {
        runCatching {
            c.app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val label = c.stager.displayName(uri, tree = kind == SourceKind.FOLDER)
        form.update { it.copy(source = SourceRef(kind, uri, label), error = null) }
    }

    // ---- repositories -------------------------------------------------------------------

    fun loadRepos() {
        if (reposLoading.value || repos.value.isNotEmpty()) return
        viewModelScope.launch {
            reposLoading.value = true
            try {
                repos.value = c.api.listRepos().filter { !it.archived }
            } catch (e: Exception) {
                form.update { it.copy(error = e.message ?: "Could not load repositories.") }
            } finally {
                reposLoading.value = false
            }
        }
    }

    fun selectRepo(r: GhRepo) = viewModelScope.launch {
        selectRepo(RepoRef(r.owner.login, r.name, r.defaultBranch, r.isPrivate), null)
    }

    private suspend fun loadPreset(owner: String, name: String) {
        try {
            val r = c.api.getRepo(owner, name)
            selectRepo(RepoRef(r.owner.login, r.name, r.defaultBranch, r.isPrivate), null)
        } catch (e: Exception) {
            form.update { it.copy(error = e.message) }
        }
    }

    private suspend fun selectRepo(ref: RepoRef, preferBranch: String?) {
        form.update { it.copy(repo = ref, branches = emptyList(), branchesLoading = true, branch = "", newBranch = false, error = null) }
        val branches = try {
            c.api.listBranches(ref.owner, ref.name)
        } catch (e: Exception) {
            form.update { it.copy(error = e.message ?: "Could not load branches.") }
            emptyList()
        }
        val wanted = preferBranch ?: ref.defaultBranch
        val chosen = when {
            wanted in branches -> wanted
            branches.isEmpty() -> wanted
            preferBranch != null -> preferBranch
            else -> branches.first()
        }
        form.update {
            it.copy(branches = branches, branchesLoading = false, branch = chosen, newBranch = chosen !in branches)
        }
    }

    private suspend fun loadProject(id: Long) {
        val p = c.db.projects().get(id) ?: return
        val kind = runCatching { SourceKind.valueOf(p.sourceKind) }.getOrDefault(SourceKind.FOLDER)
        form.update {
            it.copy(
                source = SourceRef(kind, Uri.parse(p.sourceUri), p.sourceLabel),
                message = it.message,
                subdir = p.targetSubdir,
                deleteMissing = p.deleteMissing,
            )
        }
        val ref = try {
            val r = c.api.getRepo(p.owner, p.repo)
            RepoRef(r.owner.login, r.name, r.defaultBranch, r.isPrivate)
        } catch (e: Exception) {
            RepoRef(p.owner, p.repo, p.baseBranch.ifBlank { p.branch }, false)
        }
        selectRepo(ref, p.branch)
    }

    // ---- form edits ---------------------------------------------------------------------

    fun setBranch(name: String) = form.update { it.copy(branch = name, newBranch = false) }
    fun startNewBranch() = form.update { it.copy(branch = "", newBranch = true) }
    fun setNewBranchName(v: String) = form.update { it.copy(branch = v.trim().replace(' ', '-'), newBranch = true) }
    fun setMessage(v: String) = form.update { it.copy(message = v) }
    fun setSubdir(v: String) = form.update { it.copy(subdir = v) }
    fun setDeleteMissing(v: Boolean) = form.update { it.copy(deleteMissing = v) }
    fun setRemember(v: Boolean) = form.update { it.copy(remember = v) }

    // ---- actions ------------------------------------------------------------------------

    fun review() = form.value.toSpec()?.let { c.coordinator.review(it) }
    fun pushNow() = form.value.toSpec()?.let { c.coordinator.push(it, fromReview = false) }
}
