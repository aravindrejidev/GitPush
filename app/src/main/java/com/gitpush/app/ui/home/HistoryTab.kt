package com.gitpush.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gitpush.app.LocalContainer
import com.gitpush.app.data.PushEntity
import com.gitpush.app.ui.common.EmptyState
import com.gitpush.app.ui.common.Pill
import com.gitpush.app.ui.common.humanBytes
import com.gitpush.app.ui.common.relativeTime
import com.gitpush.app.ui.common.shortSha
import com.gitpush.app.ui.theme.CodeStyle
import com.gitpush.app.ui.theme.DiffKind
import com.gitpush.app.ui.theme.diffColor

@Composable
fun HistoryTab(modifier: Modifier) {
    val c = LocalContainer.current
    val items by c.db.pushes().observeRecent().collectAsStateWithLifecycle(initialValue = emptyList())
    if (items.isEmpty()) {
        EmptyState(Icons.Outlined.History, "No pushes yet", "Every push from this phone is listed here.", modifier.fillMaxSize())
        return
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.id }) { HistoryRow(it) }
    }
}

@Composable
private fun HistoryRow(p: PushEntity) {
    val failed = p.status == "failed"
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${p.repoFullName} · ${p.branch}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(relativeTime(p.createdAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
            }
            Text(p.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (failed) {
                Text(p.error.orEmpty().lines().firstOrNull().orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (p.status == "up_to_date") {
                        Text("Already up to date", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        if (p.added > 0) Pill("+${p.added}", diffColor(DiffKind.ADDED))
                        if (p.modified > 0) Pill("~${p.modified}", diffColor(DiffKind.MODIFIED))
                        if (p.deleted > 0) Pill("−${p.deleted}", diffColor(DiffKind.DELETED))
                        Text(
                            "${shortSha(p.commitSha)} · ${humanBytes(p.bytes)}",
                            style = CodeStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
