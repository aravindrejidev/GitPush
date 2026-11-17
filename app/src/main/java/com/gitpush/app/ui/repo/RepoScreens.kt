package com.gitpush.app.ui.repo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gitpush.app.AppContainer
import com.gitpush.app.LocalContainer
import com.gitpush.app.data.GhCommit
import com.gitpush.app.ui.common.ErrorCard
import com.gitpush.app.ui.common.isoToEpoch
import com.gitpush.app.ui.common.relativeTime
import com.gitpush.app.ui.common.shortSha
import com.gitpush.app.ui.theme.CodeStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---- Repository detail --------------------------------------------------------------------

data class RepoDetailUi(
    val loading: Boolean = true,
    val branches: List<String> = emptyList(),
    val branch: String? = null,
    val commits: List<GhCommit> = emptyList(),
    val commitsLoading: Boolean = false,
    val error: String? = null,
)

class RepoDetailViewModel(private val c: AppContainer, private val owner: String, private val name: String) : ViewModel() {
    val ui = MutableStateFlow(RepoDetailUi())

    init {
        viewModelScope.launch {
            try {
                val repo = c.api.getRepo(owner, name)
                val branches = c.api.listBranches(owner, name)
                val first = if (repo.defaultBranch in branches) repo.defaultBranch else branches.firstOrNull()
                ui.update { it.copy(loading = false, branches = branches, branch = first) }
                if (first != null) loadCommits(first)
            } catch (e: Exception) {
                ui.update { it.copy(loading = false, error = e.message ?: "Could not load the repository.") }
            }
        }
    }

    fun selectBranch(b: String) {
        ui.update { it.copy(branch = b) }
        viewModelScope.launch { loadCommits(b) }
    }

    private suspend fun loadCommits(b: String) {
        ui.update { it.copy(commitsLoading = true, commits = emptyList()) }
        try {
            val commits = c.api.listCommits(owner, name, b)
            ui.update { it.copy(commits = commits, commitsLoading = false) }
        } catch (e: Exception) {
            ui.update { it.copy(commitsLoading = false, error = e.message) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(owner: String, name: String, onBack: () -> Unit, onPush: (String, String) -> Unit) {
    val c = LocalContainer.current
    val vm: RepoDetailViewModel = viewModel(
        key = "repo/$owner/$name",
        factory = viewModelFactory { initializer { RepoDetailViewModel(c, owner, name) } },
    )
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$owner/$name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Button(
                onClick = { onPush(owner, name) },
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp).height(52.dp),
            ) {
                Icon(Icons.Outlined.CloudUpload, null, Modifier.size(20.dp))
                Text("  Push to this repository")
            }
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                ui.error != null && ui.branches.isEmpty() -> Box(Modifier.padding(16.dp)) { ErrorCard(ui.error!!) }
                ui.branches.isEmpty() -> Text(
                    "This repository is empty. Your first push will create its first branch.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Branches", style = MaterialTheme.typography.titleMedium)
                                Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ui.branches.forEach { b ->
                                        FilterChip(selected = ui.branch == b, onClick = { vm.selectBranch(b) }, label = { Text(b) })
                                    }
                                }
                                Text("Recent commits", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                        if (ui.commitsLoading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                        items(ui.commits, key = { it.sha }) { cm ->
                            ListItem(
                                headlineContent = { Text(cm.commit.message.lines().firstOrNull().orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    val who = cm.commit.author?.name
                                    val whenMs = isoToEpoch(cm.commit.author?.date)?.let { relativeTime(it) }
                                    Text(listOfNotNull(shortSha(cm.sha), who, whenMs).joinToString(" · "), style = CodeStyle)
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))

// ---- Create repository --------------------------------------------------------------------

private val NAME_RE = Regex("^[A-Za-z0-9._-]+$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRepoScreen(onBack: () -> Unit, onCreated: (String, String) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = name.isNotEmpty() && NAME_RE.matches(name) && name != "." && name != ".."

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New repository") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            error?.let { ErrorCard(it) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim().replace(' ', '-') },
                label = { Text("Repository name") },
                singleLine = true,
                isError = name.isNotEmpty() && !valid,
                supportingText = { Text("Letters, numbers, dots, dashes and underscores") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Private", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (isPrivate) "Only you and people you invite can see it." else "Anyone on the internet can see it.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
            }
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val r = c.api.createRepo(name, description.trim(), isPrivate)
                            onCreated(r.owner.login, r.name)
                        } catch (e: Exception) {
                            error = e.message ?: "Could not create the repository."
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = valid && !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Create repository")
            }
        }
    }
}
