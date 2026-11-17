package com.gitpush.app.ui.push

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitpush.app.LocalContainer
import com.gitpush.app.core.FileChange
import com.gitpush.app.core.PlanData
import com.gitpush.app.data.SourceKind
import com.gitpush.app.push.Phase
import com.gitpush.app.push.PushState
import com.gitpush.app.ui.common.ErrorCard
import com.gitpush.app.ui.common.Pill
import com.gitpush.app.ui.common.SectionCard
import com.gitpush.app.ui.common.humanBytes
import com.gitpush.app.ui.common.shortSha
import com.gitpush.app.ui.theme.CodeStyle
import com.gitpush.app.ui.theme.DiffKind
import com.gitpush.app.ui.theme.diffColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushScreen(vm: PushViewModel, onBack: () -> Unit, onCreateRepo: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val state by c.coordinator.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun goBack() {
        when (state) {
            is PushState.Working -> Unit
            is PushState.Finished -> { c.coordinator.reset(); onBack() }
            is PushState.Planned, is PushState.Failed -> c.coordinator.reset()
            PushState.Idle -> onBack()
        }
    }
    BackHandler(enabled = state !is PushState.Idle) { goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state is PushState.Planned) "Review changes" else "New push") },
                navigationIcon = {
                    IconButton(onClick = ::goBack, enabled = state !is PushState.Working) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (val st = state) {
            PushState.Idle -> FormPane(vm, m, onCreateRepo)
            is PushState.Working -> WorkingPane(st, form.source?.kind, m) { c.coordinator.cancel() }
            is PushState.Planned -> ReviewPane(
                st.plan, st.spec.fullName, st.spec.branch, m,
                onPush = { c.coordinator.push(st.spec, fromReview = true) },
                onBack = { c.coordinator.reset() },
            )
            is PushState.Finished -> DonePane(st, m, onDone = { c.coordinator.reset(); onBack() })
            is PushState.Failed -> FailedPane(st.code, st.message, m, onBack = { c.coordinator.reset() })
        }
    }
}

// ---- Form ---------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormPane(vm: PushViewModel, modifier: Modifier, onCreateRepo: () -> Unit) {
    val form by vm.form.collectAsStateWithLifecycle()
    val repos by vm.repos.collectAsStateWithLifecycle()
    val reposLoading by vm.reposLoading.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var branchMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setSource(SourceKind.FOLDER, uri)
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.setSource(SourceKind.ZIP, uri)
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        form.error?.let { ErrorCard(it) }

        SectionCard("What to push") {
            val src = form.source
            if (src != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        if (src.kind == SourceKind.ZIP) Icons.Outlined.Archive else Icons.Outlined.Folder,
                        null, tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(src.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (src.kind == SourceKind.ZIP) "ZIP file" else "Folder", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderPicker.launch(null) }, Modifier.weight(1f)) { Text(if (src == null) "Choose folder" else "Other folder") }
                OutlinedButton(
                    onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    Modifier.weight(1f),
                ) { Text(if (src == null) "Choose ZIP" else "Other ZIP") }
            }
        }

        SectionCard("Where to push") {
            OutlinedButton(
                onClick = { vm.loadRepos(); showPicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(form.repo?.fullName ?: "Choose repository", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            TextButton(onClick = onCreateRepo) { Text("Create a new repository") }

            if (form.repo != null) {
                if (form.branchesLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Loading branches…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Box {
                        OutlinedButton(onClick = { branchMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (form.newBranch) "New branch" else "Branch: ${form.branch}",
                                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Icon(Icons.Filled.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = branchMenu, onDismissRequest = { branchMenu = false }) {
                            form.branches.forEach { b ->
                                DropdownMenuItem(text = { Text(b) }, onClick = { vm.setBranch(b); branchMenu = false })
                            }
                            if (form.branches.isNotEmpty()) HorizontalDivider()
                            DropdownMenuItem(text = { Text("New branch…") }, onClick = { vm.startNewBranch(); branchMenu = false })
                        }
                    }
                    if (form.newBranch) {
                        val base = form.repo?.defaultBranch
                        OutlinedTextField(
                            value = form.branch,
                            onValueChange = vm::setNewBranchName,
                            label = { Text("Branch name") },
                            singleLine = true,
                            isError = form.branch.isNotEmpty() && !form.branchValid,
                            supportingText = {
                                Text(
                                    if (form.branches.isEmpty()) "The repository is empty, so this becomes its first branch."
                                    else "Created from $base",
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        SectionCard("Commit") {
            OutlinedTextField(
                value = form.message,
                onValueChange = vm::setMessage,
                label = { Text("Commit message") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.subdir,
                onValueChange = vm::setSubdir,
                label = { Text("Folder in repository (optional)") },
                placeholder = { Text("e.g. docs/site") },
                singleLine = true,
                isError = !form.subdirValid,
                modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow(
                "Delete files missing from the source",
                "Files in the target folder that are not in your source are removed from the repository.",
                form.deleteMissing, vm::setDeleteMissing,
            )
            ToggleRow("Remember this project", "Re-push later in one tap from the Projects tab.", form.remember, vm::setRemember)
        }

        Button(
            onClick = { vm.review() },
            enabled = form.ready,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Review changes") }
        TextButton(onClick = { vm.pushNow() }, enabled = form.ready, modifier = Modifier.fillMaxWidth()) {
            Text("Push without reviewing")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showPicker) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var query by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { showPicker = false }, sheetState = sheet) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search repositories") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (reposLoading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(repos.filter { query.isBlank() || it.fullName.contains(query.trim(), true) }, key = { it.id }) { r ->
                    ListItem(
                        modifier = Modifier.clickable {
                            vm.selectRepo(r)
                            scope.launch { sheet.hide() }.invokeOnCompletion { showPicker = false }
                        },
                        headlineContent = { Text(r.fullName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = if (r.isPrivate) ({ Text("Private") }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---- Working ------------------------------------------------------------------------------

@Composable
private fun WorkingPane(st: PushState.Working, kind: SourceKind?, modifier: Modifier, onCancel: () -> Unit) {
    val first = if (kind == SourceKind.ZIP) Phase.EXTRACT else Phase.STAGE
    val steps = buildList {
        add(first); add(Phase.SCAN); add(Phase.HASH); add(Phase.REMOTE); add(Phase.DIFF)
        if (st.push) { add(Phase.PACK); add(Phase.UPLOAD) }
    }
    val current = steps.indexOfFirst { it.ordinal >= st.phase.ordinal }.let { if (it < 0) steps.size else it }

    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            (if (st.push) "Pushing to " else "Reviewing ") + st.repo,
            style = MaterialTheme.typography.titleLarge,
        )
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            steps.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        when {
                            i < current -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            i == current -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else -> Icon(Icons.Outlined.Circle, null, tint = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    Text(
                        p.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (i > current) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (st.total > 0) {
            LinearProgressIndicator(
                progress = { (st.done.toFloat() / st.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (st.phase.bytes) "${humanBytes(st.done)} of ${humanBytes(st.total)}" else "${st.done} of ${st.total} files",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (st.phase == Phase.SCAN && st.done > 0) {
                Text("${st.done} files found", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (st.note.isNotBlank()) {
            Text(st.note, style = CodeStyle, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

// ---- Review -------------------------------------------------------------------------------

@Composable
private fun ReviewPane(
    plan: PlanData,
    repo: String,
    branch: String,
    modifier: Modifier,
    onPush: () -> Unit,
    onBack: () -> Unit,
) {
    var show by remember { mutableStateOf(DiffKind.ADDED) }
    LaunchedEffect(plan) {
        show = when {
            plan.addedCount > 0 -> DiffKind.ADDED
            plan.modifiedCount > 0 -> DiffKind.MODIFIED
            else -> DiffKind.DELETED
        }
    }
    val list: List<FileChange> = when (show) {
        DiffKind.ADDED -> plan.added
        DiffKind.MODIFIED -> plan.modified
        DiffKind.DELETED -> plan.deleted
    }
    val total = when (show) {
        DiffKind.ADDED -> plan.addedCount
        DiffKind.MODIFIED -> plan.modifiedCount
        DiffKind.DELETED -> plan.deletedCount
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("$repo · $branch", style = CodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (plan.changeCount == 0) {
                        Text("Everything is already in the repository.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${plan.unchangedCount} files match, so there is nothing to push.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("${plan.changeCount} changes to push", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${plan.unchangedCount} files unchanged · about ${humanBytes(plan.uploadBytes)} to upload" +
                                if (plan.repoEmpty) " · first commit" else if (!plan.branchExists) " · new branch" else "",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (plan.deletedCount > 0) {
                        ErrorCard("${plan.deletedCount} files will be removed from the repository.")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(show == DiffKind.ADDED, { show = DiffKind.ADDED }, label = { Text("Added ${plan.addedCount}") })
                        FilterChip(show == DiffKind.MODIFIED, { show = DiffKind.MODIFIED }, label = { Text("Changed ${plan.modifiedCount}") })
                        FilterChip(show == DiffKind.DELETED, { show = DiffKind.DELETED }, label = { Text("Removed ${plan.deletedCount}") })
                    }
                }
            }
            items(list, key = { show.name + it.path }) { f -> ChangeRow(f, show) }
            if (total > list.size) {
                item { Text("Showing the first ${list.size} of $total.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp)) }
            }
            if (plan.skippedCount > 0) {
                item {
                    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${plan.skippedCount} files skipped", style = MaterialTheme.typography.titleMedium)
                        plan.skipped.take(15).forEach {
                            Text("${it.path} — ${skipReason(it.reason)}", style = CodeStyle, color = MaterialTheme.colorScheme.outline, maxLines = 2)
                        }
                        if (plan.skippedCount > 15) Text("…and ${plan.skippedCount - 15} more", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack, Modifier.weight(1f).height(52.dp)) { Text("Back") }
                Button(onClick = onPush, enabled = plan.changeCount > 0, modifier = Modifier.weight(2f).height(52.dp)) {
                    Text(if (plan.changeCount > 0) "Push ${plan.changeCount} changes" else "Nothing to push")
                }
            }
        }
    }
}

private fun skipReason(r: String) = when {
    r == "too_large" -> "over the size limit"
    r == "invalid_name" -> "name not allowed by Git"
    r == "non_utf8_name" -> "name is not valid text"
    r == "symlink" -> "symbolic link"
    r.startsWith("unreadable") -> "could not be read"
    else -> r
}

@Composable
private fun ChangeRow(f: FileChange, kind: DiffKind) {
    val color = diffColor(kind)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(
            when (kind) { DiffKind.ADDED -> "+"; DiffKind.MODIFIED -> "~"; DiffKind.DELETED -> "−" },
            style = CodeStyle, color = color, modifier = Modifier.width(14.dp),
        )
        Text(f.path, style = CodeStyle, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (kind != DiffKind.DELETED || f.size > 0) {
            Text(humanBytes(f.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ---- Done / Failed ------------------------------------------------------------------------

@Composable
private fun DonePane(st: PushState.Finished, modifier: Modifier, onDone: () -> Unit) {
    val uri = LocalUriHandler.current
    val r = st.result
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(Icons.Filled.CheckCircle, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            if (r.upToDate) "Already up to date" else "Pushed to ${st.spec.fullName}",
            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
        )
        if (r.upToDate) {
            Text("The repository already has every file.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("${shortSha(r.commitSha)} on ${r.branch}", style = CodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.added > 0) Pill("+${r.added}", diffColor(DiffKind.ADDED))
                if (r.modified > 0) Pill("~${r.modified}", diffColor(DiffKind.MODIFIED))
                if (r.deleted > 0) Pill("−${r.deleted}", diffColor(DiffKind.DELETED))
            }
            Text(
                "${humanBytes(r.bytesSent)} sent in ${"%.1f".format(st.durationMs / 1000.0)} s",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (!r.upToDate && r.commitSha != null) {
            OutlinedButton(
                onClick = { uri.openUri("https://github.com/${st.spec.fullName}/commit/${r.commitSha}") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("View commit on GitHub") }
        } else {
            OutlinedButton(
                onClick = { uri.openUri("https://github.com/${st.spec.fullName}/tree/${r.branch}") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open repository") }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
    }
}

@Composable
private fun FailedPane(code: String, message: String, modifier: Modifier, onBack: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("That didn’t go through", style = MaterialTheme.typography.titleLarge)
        ErrorCard(message)
        hintFor(code)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Code: $code", style = CodeStyle, color = MaterialTheme.colorScheme.outline)
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Edit and try again") }
    }
}

private fun hintFor(code: String): String? = when (code) {
    "AUTH_FAILED", "AUTH_REQUIRED" -> "Sign out and sign in again from the Settings tab."
    "FORBIDDEN" -> "Use a token with the repo scope (and workflow if you push .github/workflows). For an organization, approve the app under its third-party access settings."
    "NOT_FOUND" -> "Check the repository name and that your token can see it."
    "PUSH_REJECTED" -> "GitHub refused the update. A protected branch or repository rule may require a pull request: push to a new branch instead."
    "REMOTE_TREE_TRUNCATED" -> "Pick a new branch, or split the repository."
    "NETWORK" -> "Check your connection and try again."
    "RATE_LIMITED" -> "Wait a few minutes before trying again."
    else -> null
}
