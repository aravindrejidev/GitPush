package com.gitpush.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gitpush.app.LocalContainer
import com.gitpush.app.data.AppSettings
import com.gitpush.app.data.AuthState
import com.gitpush.app.data.SettingKeys
import com.gitpush.app.data.ThemeMode
import com.gitpush.app.ui.common.SectionCard
import com.gitpush.app.ui.common.humanBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsTab(modifier: Modifier) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val loaded by remember { c.settings.flow }.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
    val auth by c.auth.state.collectAsStateWithLifecycle()
    var cacheBytes by remember { mutableLongStateOf(-1L) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { cacheBytes = withContext(Dispatchers.IO) { c.stager.cacheSizeBytes() } }

    val s = loaded ?: return

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        (auth as? AuthState.SignedIn)?.let { si ->
            SectionCard("Account") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AsyncImage(
                        model = si.user.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp).clip(CircleShape),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(si.user.name ?: si.user.login, style = MaterialTheme.typography.titleMedium)
                        Text("@${si.user.login}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = { confirmSignOut = true }) { Text("Sign out") }
            }
        }

        SectionCard("Commit identity") {
            SettingField(s.authorName, "Author name", "Defaults to your GitHub name") { v ->
                scope.launch { c.settings.set(SettingKeys.authorName, v) }
            }
            SettingField(s.authorEmail, "Author email", "Defaults to your GitHub noreply address") { v ->
                scope.launch { c.settings.set(SettingKeys.authorEmail, v) }
            }
            SettingField(s.defaultMessage, "Default commit message", "") { v ->
                scope.launch { c.settings.set(SettingKeys.defaultMessage, v) }
            }
        }

        SectionCard("What gets pushed") {
            SwitchRow("Respect .gitignore files", "Skip files your .gitignore rules exclude", s.respectGitignore) {
                scope.launch { c.settings.set(SettingKeys.respectGitignore, it) }
            }
            SwitchRow("Unwrap single-folder ZIPs", "GitHub download ZIPs wrap everything in one folder", s.stripZipRoot) {
                scope.launch { c.settings.set(SettingKeys.stripZipRoot, it) }
            }
            var mb by remember { mutableFloatStateOf(s.maxFileMb.toFloat()) }
            Column {
                Text("Largest file: ${mb.toInt()} MB", style = MaterialTheme.typography.bodyLarge)
                Text("Bigger files are skipped. GitHub blocks files over 100 MB.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = mb,
                    onValueChange = { mb = it },
                    onValueChangeFinished = { scope.launch { c.settings.set(SettingKeys.maxFileMb, mb.toInt()) } },
                    valueRange = 1f..99f,
                )
            }
            SettingField(s.excludedDirs, "Never copy these names (one per line)", "", minLines = 3) { v ->
                scope.launch { c.settings.set(SettingKeys.excludedDirs, v) }
            }
            SettingField(s.extraIgnores, "Extra ignore patterns (gitignore syntax)", "e.g. *.log", minLines = 2) { v ->
                scope.launch { c.settings.set(SettingKeys.extraIgnores, v) }
            }
        }

        SectionCard("Performance") {
            Text("Worker threads", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Auto", 2 to "2", 4 to "4", 6 to "6", 8 to "8").forEach { (n, label) ->
                    FilterChip(
                        selected = s.threads == n,
                        onClick = { scope.launch { c.settings.set(SettingKeys.threads, n) } },
                        label = { Text(label) },
                    )
                }
            }
            SwitchRow("Keep copies for faster re-push", "Unchanged files are not copied again next time", s.keepStaged) {
                scope.launch { c.settings.set(SettingKeys.keepStaged, it) }
            }
            Text(
                "Stored copies: ${if (cacheBytes < 0) "…" else humanBytes(cacheBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { c.stager.clearCache() }
                    cacheBytes = 0
                }
            }) { Text("Clear stored copies") }
        }

        SectionCard("Appearance") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    FilterChip(
                        selected = s.themeMode == m,
                        onClick = { scope.launch { c.settings.set(SettingKeys.themeMode, m.name) } },
                        label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
            SwitchRow("Use wallpaper colors", "Android 12 and newer", s.dynamicColor) {
                scope.launch { c.settings.set(SettingKeys.dynamicColor, it) }
            }
        }

        SectionCard("Sign-in") {
            SettingField(s.clientId, "OAuth Client ID", "Used for “Sign in with GitHub”") { v ->
                scope.launch { c.settings.set(SettingKeys.clientId, v.trim()) }
            }
        }

        SectionCard("Data") {
            OutlinedButton(onClick = { confirmClearHistory = true }) { Text("Clear push history") }
        }

        SectionCard("App updates") {
            UpdateStatus(onCheck = { scope.launch { c.updater.check(manual = true) } })
            SettingField(s.updateRepo, "Update source (owner/repo)", "GitHub Releases of this repository are offered as updates") { v ->
                scope.launch { c.settings.set(SettingKeys.updateRepo, v.trim()) }
            }
        }

        SectionCard("About") {
            Text("GitPush ${com.gitpush.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text("Native engine ${c.engine.version}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("The token is erased from this device. Saved projects stay.") },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; c.auth.signOut() }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear history?") },
            text = { Text("This removes the list of past pushes. Nothing changes on GitHub.") },
            confirmButton = {
                TextButton(onClick = { confirmClearHistory = false; scope.launch { c.db.pushes().clear() } }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") } },
        )
    }
}

/** Keeps its own text so typing never fights the asynchronous DataStore round-trip. */
@Composable
private fun SettingField(initial: String, label: String, hint: String, minLines: Int = 1, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onChange(it) },
        label = { Text(label) },
        supportingText = if (hint.isNotEmpty()) ({ Text(hint) }) else null,
        singleLine = minLines == 1,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
