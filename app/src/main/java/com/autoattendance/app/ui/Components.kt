package com.autoattendance.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.autoattendance.app.ui.theme.LocalPalette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a")
val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
val SHORT_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

val CardShape = RoundedCornerShape(24.dp)
val FieldShape = RoundedCornerShape(14.dp)

// ---- Page structure -------------------------------------------------------------------------

enum class AppTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SCHEDULE("Schedule", Icons.Outlined.CalendarMonth),
    LOGS("Logs", Icons.Outlined.Description),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/** Bottom navigation: the selected tab gets a wide lavender pill around icon and label. */
@Composable
fun BottomBar(selected: AppTab, onSelect: (AppTab) -> Unit) {
    val p = LocalPalette.current
    Surface(color = p.card, shadowElevation = 12.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppTab.entries.forEach { tab ->
                val sel = tab == selected
                val pill = animatedColor(if (sel) p.primarySoft else Color.Transparent)
                val tint = animatedColor(if (sel) p.primary else p.textMuted)
                Column(
                    Modifier
                        .weight(1f)
                        .pressFeedback()
                        .clip(RoundedCornerShape(22.dp))
                        .background(pill)
                        // Re-selecting the current tab does nothing, so rapid taps cannot restart the transition.
                        .clickable(role = Role.Tab) { if (!sel) onSelect(tab) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, contentDescription = null, tint = tint)
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = tint,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Home header: "Auto" in the brand gradient followed by "Attendance". */
@Composable
fun BrandHeader() {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(brush = Brush.linearGradient(listOf(p.primary, p.pink.copy(alpha = 0.85f))))) {
                        append("Auto ")
                    }
                    withStyle(SpanStyle(color = p.text)) { append("Attendance") }
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text("Smart automation for your attendance", style = MaterialTheme.typography.bodyMedium, color = p.textMuted)
        }
    }
}

@Composable
fun PageHeader(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = p.text)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = p.textMuted)
        }
        action?.invoke()
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val p = LocalPalette.current
    Surface(
        onClick = onClick, enabled = enabled, shape = CircleShape, color = p.primarySofter,
        modifier = modifier.pressFeedback(enabled),
    ) {
        Icon(
            icon, contentDescription = description,
            tint = animatedColor(if (enabled) p.text else p.disabledText),
            modifier = Modifier.padding(12.dp).size(24.dp),
        )
    }
}

// ---- Cards ----------------------------------------------------------------------------------

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Surface(
        modifier.fillMaxWidth(),
        shape = CardShape,
        color = p.card,
        border = BorderStroke(1.dp, p.cardBorder),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun IconBubble(icon: ImageVector, tint: Color, background: Color, size: Dp = 46.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun CardHeader(
    icon: ImageVector,
    tint: Color,
    bubble: Color,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, tint, bubble)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.textMuted) }
        }
        Spacer(Modifier.width(8.dp))
        trailing?.invoke()
    }
}

/** Switch with an ON/OFF caption inside a soft pill, as in the card headers. */
@Composable
fun PillSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val p = LocalPalette.current
    Surface(shape = CircleShape, color = p.primarySofter) {
        Row(Modifier.padding(start = 2.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                modifier = Modifier.scale(0.78f),
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = p.primary,
                    checkedThumbColor = p.onPrimary,
                    uncheckedTrackColor = p.outline,
                    uncheckedThumbColor = Color.White,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
            val labelColor = animatedColor(if (checked) p.primary else p.text)
            FadeSwap(checked) { on ->
                Text(
                    if (on) "ON" else "OFF",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = labelColor,
                )
            }
        }
    }
}

// ---- Fields and rows ------------------------------------------------------------------------

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, color = LocalPalette.current.text)
}

/** Tappable field: filled lavender (time) or outlined (dropdowns, date range). */
@Composable
fun FieldBox(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    placeholder: Boolean = false,
    trailingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp).pressFeedback(pressedScale = 0.98f),
        shape = FieldShape,
        color = if (filled) p.primarySofter else p.card,
        border = BorderStroke(1.dp, if (filled) p.primarySoft else p.outline),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = p.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            val textColor = animatedColor(if (placeholder) p.textMuted else p.text)
            // New value (time, location, date count) cross-fades in.
            FadeSwap(text, Modifier.weight(1f)) { value ->
                Text(
                    value,
                    style = if (filled) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            trailingIcon?.let { Icon(it, null, tint = p.text) }
            trailing?.invoke()
        }
    }
}

/** Small coloured pill, e.g. a result badge. */
@Composable
fun Badge(text: String, color: Color, background: Color) {
    val fg = animatedColor(color)
    Surface(shape = CircleShape, color = animatedColor(background)) {
        FadeSwap(text, contentAlignment = Alignment.Center) { value ->
            Text(
                value,
                Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = fg,
            )
        }
    }
}

@Composable
fun CheckRow(label: String, ok: Boolean, action: String, onAction: () -> Unit) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge(if (ok) "✓" else "✗", if (ok) p.green else p.danger, if (ok) p.greenSoft else p.dangerSoft)
        Text(label, Modifier.weight(1f).padding(start = 10.dp), color = p.text)
        AnimatedVisibility(
            visible = !ok || action == "Change",
            enter = fadeIn(motionTween(Motion.SHORT)),
            exit = fadeOut(motionTween(Motion.SHORT)),
        ) { AppButton(action, onAction, kind = ButtonKind.Text) }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalPalette.current.text)
            subtitle?.let { Hint(it) }
        }
        Spacer(Modifier.width(8.dp))
        PillSwitch(checked, onChange)
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = LocalPalette.current.textMuted)
}

/** Italic note under a field, e.g. "Select a location – …". */
@Composable
fun FieldNote(text: String, error: Boolean = false) {
    val p = LocalPalette.current
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontStyle = FontStyle.Italic,
        color = if (error) p.danger else p.textMuted,
    )
}

fun formatMillis(millis: Long?, fmt: DateTimeFormatter = DATE_TIME_FMT): String =
    if (millis == null || millis <= 0) "—"
    else Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime().format(fmt)
