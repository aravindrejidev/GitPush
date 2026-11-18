package com.gitpush.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitpush.app.LocalContainer
import com.gitpush.app.ui.common.humanBytes
import com.gitpush.app.update.UpdateInfo
import com.gitpush.app.update.UpdateManager
import com.gitpush.app.update.UpdateState

private fun installOrExplain(ctx: android.content.Context, updater: UpdateManager) {
    if (!updater.install(ctx)) {
        Toast.makeText(ctx, "Allow GitPush to install apps, then come back and tap Install again.", Toast.LENGTH_LONG).show()
    }
}

/** Slim banner shown above every tab while an update is available, downloading or ready. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val st by c.updater.state.collectAsStateWithLifecycle()

    when (val s = st) {
        is UpdateState.Available -> BannerShell(modifier) {
            BannerText("Update ${s.info.version} is available", humanBytes(s.info.size))
            Button(onClick = { c.updater.download() }) { Text("Download") }
        }
        is UpdateState.Downloading -> BannerShell(modifier) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Downloading ${s.info.version}…", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (s.total > 0) {
                    LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            TextButton(onClick = { c.updater.cancelDownload() }) { Text("Cancel") }
        }
        is UpdateState.Ready -> BannerShell(modifier) {
            BannerText("Update ${s.info.version} is ready", "Tap Install to finish")
            Button(onClick = { installOrExplain(ctx, c.updater) }) { Text("Install") }
        }
        else -> Unit
    }
}

@Composable
private fun BannerShell(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BannerText(title: String, subtitle: String) {
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Full status + controls, used in Settings. */
@Composable
fun UpdateStatus(onCheck: () -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val st by c.updater.state.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Text("Installed version ${c.updater.currentVersion}", style = MaterialTheme.typography.bodyLarge)
    when (val s = st) {
        UpdateState.Idle -> Unit
        UpdateState.NotConfigured -> Text("Enter the update source below (your GitHub \"owner/repo\").", style = MaterialTheme.typography.bodyMedium, color = muted)
        UpdateState.Checking -> Text("Checking…", style = MaterialTheme.typography.bodyMedium, color = muted)
        is UpdateState.UpToDate -> Text("You have the latest version.", style = MaterialTheme.typography.bodyMedium, color = muted)
        is UpdateState.Failed -> Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        is UpdateState.Available -> {
            Text("Version ${s.info.version} is available (${humanBytes(s.info.size)}).", style = MaterialTheme.typography.bodyLarge)
            ReleaseNotes(s.info)
            Button(onClick = { c.updater.download() }) { Text("Download update") }
        }
        is UpdateState.Downloading -> {
            Text("Downloading ${s.info.version}…", style = MaterialTheme.typography.bodyLarge)
            if (s.total > 0) {
                LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${humanBytes(s.done)} of ${humanBytes(s.total)}", style = MaterialTheme.typography.bodyMedium, color = muted)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = { c.updater.cancelDownload() }) { Text("Cancel") }
        }
        is UpdateState.Ready -> {
            Text("Version ${s.info.version} is ready to install.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { installOrExplain(ctx, c.updater) }) { Text("Install") }
        }
    }
    val busy = st is UpdateState.Checking || st is UpdateState.Downloading
    OutlinedButton(onClick = onCheck, enabled = !busy) { Text("Check for updates") }
}

@Composable
private fun ReleaseNotes(info: UpdateInfo) {
    if (info.notes.isBlank()) return
    Text(
        info.notes.lines().take(8).joinToString("\n"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
