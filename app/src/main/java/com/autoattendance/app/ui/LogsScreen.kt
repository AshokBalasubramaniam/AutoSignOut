@file:OptIn(ExperimentalLayoutApi::class)

package com.autoattendance.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoattendance.app.data.AttemptLog
import com.autoattendance.app.data.AttemptResult
import com.autoattendance.app.data.LogLevel
import com.autoattendance.app.data.Operation
import com.autoattendance.app.data.Repository
import com.autoattendance.app.data.Trigger
import com.autoattendance.app.ui.theme.LocalPalette

@Composable
fun LogsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val repo = remember { Repository.get(ctx) }
    val attempts by repo.attempts.collectAsState(initial = emptyList())
    val events by repo.log.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    // Filters: none selected in a group = show all of that group.
    var ops by rememberSaveable { mutableStateOf(setOf<String>()) }
    var results by rememberSaveable { mutableStateOf(setOf<String>()) }
    val shown = attempts.filter {
        (ops.isEmpty() || it.operation.name in ops) && (results.isEmpty() || it.result.name in results)
    }

    val reduce = LocalReduceMotion.current
    // Attempt cards fade in/out and slide into their new place when filters or new results change the list.
    fun Modifier.listMotion(scope: LazyItemScope) = with(scope) {
        animateItem(
            fadeInSpec = Motion.tween(reduce, Motion.MEDIUM),
            placementSpec = Motion.spring(reduce),
            fadeOutSpec = Motion.tween(reduce, Motion.SHORT),
        )
    }

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            PageHeader("Automation Logs", "Every Sign In and Sign Out attempt") {
                CircleIconButton(
                    Icons.Outlined.DeleteOutline, "Clear logs", { confirmClear = true },
                    enabled = attempts.isNotEmpty() || events.isNotEmpty(),
                )
            }
        }
        item(key = "tabs") {
            SegmentedToggle(listOf("Attempts (${attempts.size})", "Diagnostics"), tab, { tab = it })
        }

        if (tab == 0) {
            item(key = "filters") {
                FlowRow(Modifier.listMotion(this), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Operation.entries.forEach { op ->
                        FilterPill(op.label, op.name in ops) { ops = ops.toggle(op.name) }
                    }
                    AttemptResult.entries.forEach { r ->
                        FilterPill(r.label, r.name in results) { results = results.toggle(r.name) }
                    }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        if (attempts.isEmpty()) "No automation attempts yet" else "No attempts match the filters",
                        Modifier.listMotion(this),
                    )
                }
            }
            items(shown, key = { it.id }) { AttemptCard(it, Modifier.listMotion(this)) }
        } else {
            item(key = "eventsHint") { Hint("Step-by-step events, newest first. Useful when an attempt fails.", Modifier.listMotion(this)) }
            if (events.isEmpty()) item(key = "eventsEmpty") { EmptyState("No events yet", Modifier.listMotion(this)) }
            // Diagnostics update often while a run is in progress, so these rows are deliberately not animated.
            items(events) { e ->
                val color = when (e.level) {
                    LogLevel.ERROR -> p.danger
                    LogLevel.WARN -> p.amber
                    LogLevel.INFO -> p.text
                }
                Surface(shape = FieldShape, color = p.card, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(formatMillis(e.time), style = MaterialTheme.typography.labelSmall, color = p.textMuted)
                        Text(e.message, style = MaterialTheme.typography.bodySmall, color = color)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AppAlertDialog(
            onDismissed = { confirmClear = false },
            title = "Clear all logs?",
            buttons = { c ->
                AppButton("Cancel", { c.close() }, kind = ButtonKind.Text)
                AppButton("Clear", { c.close { repo.clearLogs() } })
            },
        ) { Text("All automation attempts and diagnostic events will be deleted. This cannot be undone.", color = p.text) }
    }
}

private fun Set<String>.toggle(v: String) = if (v in this) this - v else this + v

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = animatedColor(if (selected) p.primary else p.primarySofter),
        modifier = Modifier.pressFeedback(pressedScale = 0.94f),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = animatedColor(if (selected) p.onPrimary else p.text),
        )
    }
}

/** Centred icon and message for an empty list. */
@Composable
private fun EmptyState(text: String, modifier: Modifier) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBubble(Icons.Outlined.Inbox, p.textMuted, p.primarySofter, size = 56.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = p.textMuted)
    }
}

@Composable
private fun AttemptCard(a: AttemptLog, modifier: Modifier) {
    val p = LocalPalette.current
    val style = opStyle(a.operation)
    val (fg, bg) = when (a.result) {
        AttemptResult.SUCCESS -> p.green to p.greenSoft
        AttemptResult.FAILED -> p.danger to p.dangerSoft
        AttemptResult.SKIPPED -> p.amber to p.amberSoft
        AttemptResult.PENDING -> p.textMuted to p.primarySofter
    }
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(style.icon, style.tint, style.bubble, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // e.g. "09:27 AM – Sign In – Office"
                Text(
                    listOf(
                        formatMillis(a.scheduledAt ?: a.startedAt, TIME_FMT),
                        a.operation.label,
                        a.location ?: "No location",
                    ).joinToString(" – "),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = p.text,
                )
                Text(
                    formatMillis(a.startedAt, DATE_FMT) + if (a.trigger != Trigger.SCHEDULED) " · ${a.trigger.label}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textMuted,
                )
            }
            Badge(a.result.label, fg, bg)
        }
        Surface(shape = FieldShape, color = p.primarySofter, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LogLine("Scheduled", if (a.scheduledAt != null) formatMillis(a.scheduledAt, TIME_FMT) else "Manual test")
                LogLine("Started", formatMillis(a.startedAt, TIME_FMT))
                LogLine("Executed", if (a.executedAt != null) formatMillis(a.executedAt, TIME_FMT) else "Not executed")
                LogLine("Status", "${a.previousStatus.label} → ${a.finalStatus.label}")
            }
        }
        a.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (a.result == AttemptResult.FAILED) p.danger else p.text,
            )
        }
    }
}

@Composable
private fun LogLine(label: String, value: String) {
    val p = LocalPalette.current
    Row {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = p.textMuted)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = p.text)
    }
}
