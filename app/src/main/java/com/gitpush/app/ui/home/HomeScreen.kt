package com.gitpush.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private enum class Tab(val title: String, val icon: ImageVector) {
    PROJECTS("Projects", Icons.Outlined.Folder),
    REPOS("Repositories", Icons.Outlined.Storage),
    HISTORY("History", Icons.Outlined.History),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNewPush: () -> Unit,
    onOpenProject: (Long) -> Unit,
    onOpenRepo: (String, String) -> Unit,
    onCreateRepo: () -> Unit,
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = Tab.entries[tabIndex]

    Scaffold(
        topBar = { TopAppBar(title = { Text(tab.title) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = i == tabIndex,
                        onClick = { tabIndex = i },
                        icon = { Icon(t.icon, contentDescription = t.title) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
        floatingActionButton = {
            when (tab) {
                Tab.PROJECTS -> ExtendedFloatingActionButton(
                    onClick = onNewPush,
                    icon = { Icon(Icons.Outlined.CloudUpload, null) },
                    text = { Text("New push") },
                )
                Tab.REPOS -> ExtendedFloatingActionButton(
                    onClick = onCreateRepo,
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New repository") },
                )
                else -> Unit
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            UpdateBanner(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Box(Modifier.weight(1f)) {
                val m = Modifier.fillMaxSize()
                when (tab) {
                    Tab.PROJECTS -> ProjectsTab(m, onNewPush, onOpenProject)
                    Tab.REPOS -> ReposTab(m, onOpenRepo)
                    Tab.HISTORY -> HistoryTab(m)
                    Tab.SETTINGS -> SettingsTab(m)
                }
            }
        }
    }
}
