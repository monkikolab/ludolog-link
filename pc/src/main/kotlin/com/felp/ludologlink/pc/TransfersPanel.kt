package com.felp.ludologlink.pc

import com.felp.ludolog.kit.ui.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.felp.ludolog.kit.Format
import com.felp.ludolog.kit.ui.KitIcons
import java.awt.Desktop

@Composable
fun TransfersPanel(app: AppState) {
    val t = app.transfers
    var expanded by remember { mutableStateOf(true) }
    val running = t.items.firstOrNull { it.state == TState.RUNNING }
    val queued = t.items.count { it.state == TState.QUEUED }
    val failed = t.items.count { it.state == TState.FAILED }
    val pending = t.items.filter { it.state == TState.QUEUED || it.state == TState.RUNNING || it.state == TState.WAITING }
    val left = pending.sumOf { it.size - it.done }
    // Abierto mientras hay trabajo; al acabar se pliega para dejar sitio a la lista.
    LaunchedEffect(pending.isEmpty(), failed) { expanded = pending.isNotEmpty() || failed > 0 }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Transfers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            val summary = buildList {
                if (running != null) add(if (running.upload) "uploading" else "downloading")
                if (queued > 0) add("$queued queued")
                if (pending.isNotEmpty()) {
                    add("${Format.size(left)} left")
                    val speed = running?.speed ?: 0.0
                    if (speed > 0) add("~" + Format.duration((left / speed).toLong()))
                }
                if (failed > 0) add("$failed failed")
                if (pending.isEmpty() && failed == 0) add("all done")
            }.joinToString(" · ")
            Text(summary, Modifier.weight(1f), color = if (failed > 0) Look.danger else MaterialTheme.colorScheme.onSurfaceVariant)
            if (pending.isNotEmpty()) LTextButton(onClick = { t.cancelAll() }) { Text("Cancel all") }
            if (t.items.any { it.state == TState.DONE || it.state == TState.CANCELLED }) {
                LTextButton(onClick = { t.clearFinished() }) { Text("Clear finished") }
            }
            Text(if (expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 168.dp).padding(horizontal = 20.dp)) {
                items(t.items, key = { System.identityHashCode(it) }) { x -> TransferRow(app, x) }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun TransferRow(app: AppState, x: Transfer) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (x.upload) KitIcons.ArrowUp else KitIcons.ArrowDown, if (x.upload) "Upload" else "Download",
            Modifier.size(18.dp), tint = if (x.upload) Look.accent else Look.ok)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.width(360.dp)) {
            Text(x.name.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(when {
                x.from != null -> "${x.fromName} → ${x.consoleName} · ${x.system}"
                x.upload -> "→ ${x.consoleName} · ${x.system}"
                else -> "${x.consoleName} → ${x.local.parent}"
            },
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        LinearProgressIndicator(
            progress = { if (x.size > 0) (x.done.toFloat() / x.size).coerceIn(0f, 1f) else 1f },
            modifier = Modifier.weight(1f),
            color = when (x.state) { TState.FAILED -> Look.danger; TState.WAITING -> Look.warn; TState.DONE -> Look.ok; TState.CANCELLED -> Look.off; else -> Look.accent },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
        Spacer(Modifier.width(14.dp))
        val status = when (x.state) {
            TState.QUEUED -> "queued · ${Format.size(x.size)}"
            TState.WAITING -> (x.error ?: "connection lost: trying again") +
                (if (x.done > 0) " · ${Format.size(x.done)} of ${Format.size(x.size)} kept" else "")
            TState.RUNNING -> buildString {
                append("${Format.size(x.done)} of ${Format.size(x.size)}")
                if (x.speed > 0) {
                    append(" · ${Format.size(x.speed.toLong())}/s")
                    append(" · " + Format.duration(((x.size - x.done) / x.speed).toLong()))
                }
            }
            TState.DONE -> "done · ${Format.size(x.size)}"
            TState.CANCELLED -> "cancelled"
            TState.FAILED -> x.error ?: "failed"
        }
        Text(status, Modifier.width(260.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = if (x.state == TState.FAILED) Look.danger else MaterialTheme.colorScheme.onSurfaceVariant)
        when (x.state) {
            TState.QUEUED, TState.RUNNING, TState.WAITING ->
                IconButton(onClick = { app.transfers.cancel(x) }) { Icon(KitIcons.Close, "Cancel", Modifier.size(18.dp)) }
            TState.FAILED, TState.CANCELLED ->
                IconButton(onClick = { app.transfers.retry(x) }) { Icon(KitIcons.Refresh, "Retry", Modifier.size(18.dp)) }
            TState.DONE ->
                if (!x.upload) IconButton(onClick = { openFolder(x) }) { Icon(KitIcons.Folder, "Open folder", Modifier.size(18.dp)) }
                else Spacer(Modifier.width(48.dp))
        }
    }
}

private fun openFolder(x: Transfer) {
    // Explorador con el archivo marcado.
    runCatching { ProcessBuilder("explorer.exe", "/select,", x.local.absolutePath).start() }
        .onFailure { runCatching { Desktop.getDesktop().open(x.local.parentFile) } }
}
