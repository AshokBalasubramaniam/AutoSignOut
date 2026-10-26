package com.autoattendance.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Help
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoattendance.app.automation.AttendanceAccessibilityService
import com.autoattendance.app.data.AttendanceStatus
import com.autoattendance.app.data.AutomationSettings
import com.autoattendance.app.data.Operation
import com.autoattendance.app.data.Repository
import com.autoattendance.app.data.StatusInfo
import com.autoattendance.app.schedule.AlarmScheduler
import com.autoattendance.app.schedule.ScheduleCalculator
import com.autoattendance.app.ui.theme.LocalPalette
import java.time.format.DateTimeFormatter

private val NEXT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a")

/** Home: the automation overview only. Everything that is configured lives on the Schedule and Settings tabs. */
@Composable
fun HomeScreen(
    resumeTick: Int,
    modifier: Modifier,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val repo = remember { Repository.get(ctx) }
    val settings by repo.settings.collectAsState()
    val status by repo.status.collectAsState()
    val progress by repo.runProgress.collectAsState()
    val serviceConnected by AttendanceAccessibilityService.connected.collectAsState()
    val p = LocalPalette.current

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandHeader()
        MasterBanner(settings) { on ->
            repo.updateSettings { it.copy(enabled = on) }
            AlarmScheduler.reschedule(ctx)
        }
        ProgressBanner(progress)
        OverviewCard(settings, status, resumeTick, onOpenSchedule)
        val issues = remember(settings, serviceConnected, resumeTick) { findIssues(ctx, settings, serviceConnected) }
        // Keep the last list while the card animates away after the final issue is fixed.
        val shownIssues = remember { arrayOf(issues) }
        if (issues.isNotEmpty()) shownIssues[0] = issues
        ExpandFade(issues.isNotEmpty()) { AttentionCard(shownIssues[0], onOpenSchedule, onOpenSettings) }
    }
}

/** Gradient banner with the master switch for both operations. */
@Composable
private fun MasterBanner(settings: AutomationSettings, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    val on = settings.enabled
    val enabledOps = Operation.entries.filter { settings.schedule(it).enabled }
    val start = animateColorAsState(if (on) p.primary else Color(0xFF8E8AA6), motionTween(Motion.LONG), label = "bannerStart")
    val end = animateColorAsState(if (on) Color(0xFF8B5CF6) else Color(0xFFB5B1C9), motionTween(Motion.LONG), label = "bannerEnd")
    Box(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            // Gradient read in the draw phase only: the colour animation never recomposes the banner.
            .drawBehind { drawRect(Brush.linearGradient(listOf(start.value, end.value))) }
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.SmartToy, null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                FadeSwap(on) { isOn ->
                    Text(
                        if (isOn) "Automation is ON" else "Automation is OFF",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                FadeSwap(
                    when {
                        !on -> "Nothing will run until you switch it on"
                        enabledOps.isEmpty() -> "Turn on Sign In or Sign Out in Schedule"
                        else -> enabledOps.joinToString(" & ") { it.label } + " scheduled"
                    },
                ) { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                }
            }
            Switch(
                checked = on,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Color.White,
                    checkedThumbColor = p.primary,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.35f),
                    uncheckedThumbColor = Color.White,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

@Composable
private fun OverviewCard(settings: AutomationSettings, status: StatusInfo, resumeTick: Int, onOpenSchedule: () -> Unit) {
    val p = LocalPalette.current
    AppCard {
        Text("Automation Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.text)

        // Current status
        Surface(shape = FieldShape, color = p.primarySofter, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Current attendance status", style = MaterialTheme.typography.bodyMedium, color = p.textMuted)
                    Spacer(Modifier.size(6.dp))
                    FadeSwap(status.status) { StatusChip(it) }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Last seen", style = MaterialTheme.typography.labelSmall, color = p.textMuted)
                    Text(formatMillis(status.observedAt, SHORT_DATE_FMT), style = MaterialTheme.typography.bodySmall, color = p.text)
                    if (status.observedAt > 0) {
                        Text(formatMillis(status.observedAt, TIME_FMT), style = MaterialTheme.typography.bodySmall, color = p.text)
                    }
                }
            }
        }

        // Next runs
        Operation.entries.forEach { op -> NextRunRow(op, settings, resumeTick, onOpenSchedule) }

        HorizontalDivider(color = p.cardBorder)

        // Last successful action – glows green once when a new Sign In / Sign Out is verified.
        val glow = remember { Animatable(0f) }
        val seenAt = remember { longArrayOf(status.lastActionAt) }
        val reduce = LocalReduceMotion.current
        LaunchedEffect(status.lastActionAt) {
            if (status.lastActionAt > seenAt[0]) {
                seenAt[0] = status.lastActionAt
                if (!reduce) {
                    glow.snapTo(1f)
                    glow.animateTo(0f, tween(1400, easing = Motion.Standard))
                }
            }
        }
        val glowColor = p.greenSoft
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    if (glow.value > 0f) drawRoundRect(glowColor.copy(alpha = glow.value), cornerRadius = CornerRadius(14.dp.toPx()))
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FadeSwap(status.lastAction != null) { done ->
                if (done) IconBubble(Icons.Rounded.CheckCircle, p.green, p.greenSoft, size = 40.dp)
                else IconBubble(Icons.Outlined.History, p.primary, p.primarySoft, size = 40.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Last successful action", style = MaterialTheme.typography.bodyMedium, color = p.text)
                Text(
                    status.lastAction?.let { op ->
                        op.label + (status.lastActionLocation?.let { " · $it" } ?: "")
                    } ?: "None yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textMuted,
                )
            }
            if (status.lastAction != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatMillis(status.lastActionAt, SHORT_DATE_FMT), style = MaterialTheme.typography.bodySmall, color = p.text)
                    Text(formatMillis(status.lastActionAt, TIME_FMT), style = MaterialTheme.typography.bodySmall, color = p.textMuted)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: AttendanceStatus) {
    val p = LocalPalette.current
    val (icon, tint, bg) = when (status) {
        AttendanceStatus.SIGNED_IN -> Triple(Icons.Rounded.CheckCircle, p.green, p.greenSoft)
        AttendanceStatus.SIGNED_OUT -> Triple(Icons.AutoMirrored.Rounded.Logout, p.pink, p.pinkSoft)
        AttendanceStatus.UNKNOWN -> Triple(Icons.AutoMirrored.Rounded.Help, p.textMuted, p.primarySoft)
    }
    Surface(shape = CircleShape, color = bg) {
        Row(Modifier.padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(status.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.text)
        }
    }
}

/** "Next Sign In – Mon, 26 Oct · 9:27 AM – Office" with an Active / Paused / Off badge. Opens Schedule. */
@Composable
private fun NextRunRow(op: Operation, settings: AutomationSettings, resumeTick: Int, onOpenSchedule: () -> Unit) {
    val p = LocalPalette.current
    val style = opStyle(op)
    val schedule = settings.schedule(op)
    // Refreshes every minute and on resume, so it never shows a time that has passed.
    val now = rememberNow(resumeTick)
    val next = remember(schedule, now) { ScheduleCalculator.nextRun(schedule, now) }
    val (badge, fg, bg) = when {
        !schedule.enabled -> Triple("Off", p.disabledText, p.disabledBg)
        !settings.enabled -> Triple("Paused", p.amber, p.amberSoft)
        schedule.location == null -> Triple("No location", p.danger, p.dangerSoft)
        next == null -> Triple("No date", p.danger, p.dangerSoft)
        else -> Triple("Active", p.green, p.greenSoft)
    }
    Surface(
        onClick = onOpenSchedule, shape = FieldShape, color = p.card,
        modifier = Modifier.fillMaxWidth().pressFeedback(pressedScale = 0.98f),
    ) {
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(style.icon, style.tint, style.bubble, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Next ${op.label}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = p.text)
                // Changes at most once per run, so a cross-fade here is not "frequently updated data".
                FadeSwap(next?.format(NEXT_FMT) ?: "No upcoming date") {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = p.text)
                }
                Text(schedule.location ?: "Location not selected", style = MaterialTheme.typography.bodySmall, color = p.textMuted)
            }
            Badge(badge, fg, bg)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = p.textMuted)
        }
    }
}

/** Something that would stop the automation from running, with the tab that fixes it. */
private data class Issue(val text: String, val onSchedule: Boolean)

private fun findIssues(ctx: android.content.Context, settings: AutomationSettings, serviceConnected: Boolean): List<Issue> =
    buildList {
        if (!SystemChecks.isInstalled(ctx, settings.targetPackage)) add(Issue("Select the attendance app", onSchedule = false))
        if (!serviceConnected) add(Issue("Turn on the accessibility service", onSchedule = false))
        if (!SystemChecks.notificationsEnabled(ctx)) add(Issue("Allow notifications", onSchedule = false))
        Operation.entries.forEach { op ->
            val s = settings.schedule(op)
            if (s.enabled && s.location == null) add(Issue("Select a ${op.label} location", onSchedule = true))
        }
    }

@Composable
private fun AttentionCard(issues: List<Issue>, onOpenSchedule: () -> Unit, onOpenSettings: () -> Unit) {
    val p = LocalPalette.current
    AppCard(Modifier.animateContentSize(motionTween(Motion.MEDIUM))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, tint = p.amber)
            Spacer(Modifier.width(8.dp))
            Text("Needs attention", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.text)
        }
        issues.forEach { issue ->
            Surface(
                onClick = if (issue.onSchedule) onOpenSchedule else onOpenSettings,
                shape = FieldShape,
                color = p.amberSoft,
                modifier = Modifier.fillMaxWidth().pressFeedback(pressedScale = 0.98f),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(issue.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = p.text)
                    Text(if (issue.onSchedule) "Schedule" else "Settings", style = MaterialTheme.typography.labelMedium, color = p.amber)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = p.amber)
                }
            }
        }
    }
}
