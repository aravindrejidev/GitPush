package com.gitpush.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.gitpush.app.AppContainer
import com.gitpush.app.LocalContainer
import com.gitpush.app.data.GhRepo
import com.gitpush.app.ui.common.EmptyState
import com.gitpush.app.ui.common.ErrorCard
import com.gitpush.app.ui.common.isoToEpoch
import com.gitpush.app.ui.common.relativeTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReposUi(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val repos: List<GhRepo> = emptyList(),
    val query: String = "",
    val error: String? = null,
) {
    val filtered: List<GhRepo>
        get() = if (query.isBlank()) repos else repos.filter { it.fullName.contains(query.trim(), ignoreCase = true) }
}

class ReposViewModel(private val c: AppContainer) : ViewModel() {
    val ui = MutableStateFlow(ReposUi())

    init { load(initial = true) }

    fun setQuery(q: String) = ui.update { it.copy(query = q) }

    fun load(initial: Boolean = false) {
        viewModelScope.launch {
            ui.update { it.copy(loading = initial, refreshing = !initial, error = null) }
            try {
                val repos = c.api.listRepos()
                ui.update { it.copy(repos = repos, loading = false, refreshing = false) }
            } catch (e: Exception) {
                ui.update { it.copy(loading = false, refreshing = false, error = e.message ?: "Could not load repositories.") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposTab(modifier: Modifier, onOpenRepo: (String, String) -> Unit) {
    val c = LocalContainer.current
    val vm: ReposViewModel = viewModel(factory = viewModelFactory { initializer { ReposViewModel(c) } })
    val ui by vm.ui.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = ui.query,
            onValueChange = vm::setQuery,
            placeholder = { Text("Search repositories") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 8.dp),
        )
        when {
            ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            ui.error != null && ui.repos.isEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ErrorCard(ui.error!!)
                Button(onClick = { vm.load(true) }) { Text("Try again") }
            }
            ui.repos.isEmpty() -> EmptyState(Icons.Outlined.Storage, "No repositories", "Create one with the button below.")
            else -> PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = { vm.load() }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(ui.filtered, key = { it.id }) { r ->
                        ListItem(
                            modifier = Modifier.clickable { onOpenRepo(r.owner.login, r.name) },
                            headlineContent = { Text(r.fullName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                val sub = listOfNotNull(
                                    r.description?.takeIf { it.isNotBlank() },
                                    isoToEpoch(r.pushedAt)?.let { "Updated ${relativeTime(it)}" },
                                ).joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = { if (r.isPrivate) Icon(Icons.Filled.Lock, "Private", tint = MaterialTheme.colorScheme.outline) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}
