package com.autoattendance.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoattendance.app.schedule.ScheduleCalculator
import com.autoattendance.app.ui.theme.LocalPalette
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

private val MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

/** How far ahead dates can be picked. */
private const val MONTHS_AHEAD = 12L

/**
 * The current time, updated at the start of every minute (and again whenever [key] changes, e.g. on
 * resume). Lists of upcoming runs use it, so a run that has passed disappears and the next one moves up.
 */
@Composable
fun rememberNow(key: Any? = null): LocalDateTime {
    val now by produceState(LocalDateTime.now(), key) {
        while (true) {
            val n = LocalDateTime.now()
            delay(60_000L - (n.second * 1000L + n.nano / 1_000_000))
            value = LocalDateTime.now()
        }
    }
    return now
}

/**
 * Calendar where any number of dates can be tapped on and off. Selected dates are highlighted;
 * Saturday, Sunday and past days are disabled. Nothing is saved until "Done".
 */
@Composable
fun MultiDatePickerDialog(initial: Set<LocalDate>, onDismissed: () -> Unit, onConfirm: (Set<LocalDate>) -> Unit) {
    val p = LocalPalette.current
    val today = remember { LocalDate.now() }
    val firstMonth = remember { YearMonth.from(today) }
    val lastMonth = remember { firstMonth.plusMonths(MONTHS_AHEAD) }
    // Past dates cannot run any more, so they are dropped from the selection.
    var selected by remember { mutableStateOf(initial.filterNot { it.isBefore(today) }.toSet()) }
    var month by remember {
        mutableStateOf(selected.minOrNull()?.let(YearMonth::from)?.coerceIn(firstMonth, lastMonth) ?: firstMonth)
    }

    val reduce = LocalReduceMotion.current
    AppDialog(onDismissed, maxWidth = 420) { c ->
        Surface(shape = CardShape, color = p.card) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Select dates", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = p.text)
                Hint("Tap dates to select or unselect them. Saturday and Sunday are not available.")

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }, enabled = month > firstMonth) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month")
                    }
                    Text(
                        month.format(MONTH_FMT),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = p.text,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < lastMonth) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month")
                    }
                }

                // The month slides in from the side you navigated towards.
                AnimatedContent(
                    targetState = month,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(Motion.tween(reduce, Motion.MEDIUM, Motion.Enter)) { w -> dir * w / 4 } +
                            fadeIn(Motion.tween(reduce, Motion.MEDIUM))) togetherWith
                            (slideOutHorizontally(Motion.tween(reduce, Motion.SHORT, Motion.Exit)) { w -> -dir * w / 4 } +
                                fadeOut(Motion.tween(reduce, Motion.SHORT))) using
                            SizeTransform(clip = true) { _, _ -> Motion.tween(reduce, Motion.MEDIUM) }
                    },
                    label = "month",
                ) { shown ->
                    MonthGrid(shown, today, selected) { d -> selected = if (d in selected) selected - d else selected + d }
                }

                FadeSwap(
                    when (selected.size) {
                        0 -> "No dates selected"
                        1 -> "1 date selected"
                        else -> "${selected.size} dates selected"
                    },
                ) { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textMuted) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppButton("Clear", { selected = emptySet() }, kind = ButtonKind.Text, enabled = selected.isNotEmpty())
                    Spacer(Modifier.weight(1f))
                    AppButton("Cancel", { c.close() }, kind = ButtonKind.Text)
                    Spacer(Modifier.width(4.dp))
                    AppButton("Done", { c.close { onConfirm(selected) } })
                }
            }
        }
    }
}

/** Monday-first month grid. */
@Composable
private fun MonthGrid(month: YearMonth, today: LocalDate, selected: Set<LocalDate>, onToggle: (LocalDate) -> Unit) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            DayOfWeek.entries.forEach { d ->
                val weekend = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY
                Text(
                    d.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (weekend) p.disabledText else p.textMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        val leading = month.atDay(1).dayOfWeek.value - 1
        val cells: List<LocalDate?> = List(leading) { null } + (1..month.lengthOfMonth()).map(month::atDay)
        cells.chunked(7).forEach { week ->
            Row {
                (week + List(7 - week.size) { null }).forEach { date ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (date != null) DayCell(date, today, date in selected, onToggle)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, today: LocalDate, selected: Boolean, onToggle: (LocalDate) -> Unit) {
    val p = LocalPalette.current
    val disabled = ScheduleCalculator.isWeekend(date) || date.isBefore(today)
    val isToday = date == today
    val fill = animatedColor(
        when {
            selected -> p.primary
            disabled -> Color.Transparent
            else -> p.primarySofter
        },
    )
    val textColor = animatedColor(
        when {
            selected -> p.onPrimary
            disabled -> p.disabledText
            else -> p.text
        },
    )
    Box(
        Modifier
            .fillMaxSize()
            .pressFeedback(enabled = !disabled, pressedScale = 0.88f)
            .clip(CircleShape)
            .background(fill)
            .then(if (isToday && !selected) Modifier.border(BorderStroke(1.5.dp, p.primary), CircleShape) else Modifier)
            .clickable(enabled = !disabled) { onToggle(date) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${date.dayOfMonth}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
        )
    }
}
