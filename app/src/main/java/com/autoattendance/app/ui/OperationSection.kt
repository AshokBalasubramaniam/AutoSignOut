@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.autoattendance.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoattendance.app.data.AutomationSettings
import com.autoattendance.app.data.Operation
import com.autoattendance.app.data.OperationSchedule
import com.autoattendance.app.data.ScheduleMode
import com.autoattendance.app.schedule.DayDecision
import com.autoattendance.app.schedule.ScheduleCalculator
import com.autoattendance.app.ui.theme.LocalPalette
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale


typealias ScheduleEdit = (Operation, (OperationSchedule) -> OperationSchedule) -> Unit

data class OpStyle(val icon: ImageVector, val tint: Color, val bubble: Color)

/** Green for Sign In, pink for Sign Out – used for every icon of that operation. */
@Composable
fun opStyle(op: Operation): OpStyle {
    val p = LocalPalette.current
    return if (op == Operation.SIGN_IN) OpStyle(Icons.AutoMirrored.Rounded.Login, p.green, p.greenSoft)
    else OpStyle(Icons.AutoMirrored.Rounded.Logout, p.pink, p.pinkSoft)
}

/**
 * Schedule-page card for one operation. Sign In and Sign Out each get their own, with independent
 * switch, time, location, remarks, schedule, date check and the next runs.
 */
@Composable
fun ScheduleCard(
    op: Operation,
    settings: AutomationSettings,
    resumeTick: Int,
    /** Saves and recalculates the alarms. */
    update: ScheduleEdit,
) {
    val p = LocalPalette.current
    val schedule = settings.schedule(op)
    val style = opStyle(op)
    fun edit(t: (OperationSchedule) -> OperationSchedule) = update(op, t)
    var showLocation by remember { mutableStateOf(false) }
    // Refreshes every minute: once a run passes, it drops off and the next chosen date moves up.
    val now = rememberNow(resumeTick)
    val upcoming = remember(schedule, now) { ScheduleCalculator.upcoming(schedule, now, 5) }
    val active = settings.enabled && schedule.enabled

    AppCard {
        CardHeader(
            style.icon, style.tint, style.bubble,
            "Automatic ${op.label}",
            if (op == Operation.SIGN_IN) "Automatically sign in at the specified time"
            else "Automatically sign out at the specified time",
        ) { PillSwitch(schedule.enabled, { on -> edit { it.copy(enabled = on) } }) }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("${op.label} time")
                TimeField(op, schedule, ::edit)
            }
            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel("${op.label} location")
                FieldBox(
                    Icons.Outlined.LocationOn, schedule.location ?: "Select location",
                    onClick = { showLocation = true },
                    placeholder = schedule.location == null,
                    trailingIcon = Icons.Rounded.KeyboardArrowDown,
                )
            }
        }
        ExpandFade(schedule.location == null) {
            FieldNote("Select a location – the automation never picks one by itself and will not run without it.")
        }


        ScheduleBlock(schedule, ::edit)
        CheckDate(schedule)

        FieldLabel("Upcoming")
        // Grows/shrinks smoothly when a run passes or the selection changes (at most once a minute).
        Column(Modifier.animateContentSize(motionTween(Motion.MEDIUM)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (upcoming.isEmpty()) Hint("No upcoming date matches your selection.")
        upcoming.forEachIndexed { i, at ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (i == 0 && active) style.bubble else p.primarySofter),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = if (i == 0 && active) style.tint else p.textMuted)
                }
                Spacer(Modifier.width(12.dp))
                Text(at.format(DATE_FMT), Modifier.weight(1f), color = p.text)
                Text(at.format(TIME_FMT), fontWeight = FontWeight.SemiBold, color = p.text)
            }
        }
        }
        ExpandFade(!active && upcoming.isNotEmpty()) {
            FieldNote(if (!settings.enabled) "Paused – the master Automation switch on Home is OFF." else "Paused – automatic ${op.label} is OFF.")
        }
    }

    if (showLocation) {
        LocationDialog(
            title = "Select your ${op.label} Location",
            options = (settings.locationOptionList + listOfNotNull(schedule.location)).distinct(),
            selected = schedule.location,
            onDismissed = { showLocation = false },
            onPick = { loc -> edit { it.copy(location = loc) } },
        )
    }
}

@Composable
private fun TimeField(op: Operation, schedule: OperationSchedule, edit: ((OperationSchedule) -> OperationSchedule) -> Unit) {
    var show by remember { mutableStateOf(false) }
    FieldBox(
        Icons.Outlined.Schedule, LocalTime.of(schedule.hour, schedule.minute).format(TIME_FMT),
        onClick = { show = true }, filled = true,
    )
    if (show) {
        val state = rememberTimePickerState(schedule.hour, schedule.minute, is24Hour = false)
        AppAlertDialog(
            onDismissed = { show = false },
            title = "${op.label} time",
            buttons = { c ->
                AppButton("Cancel", { c.close() }, kind = ButtonKind.Text)
                AppButton("OK", { c.close { edit { it.copy(hour = state.hour, minute = state.minute) } } })
            },
        ) { TimePicker(state, Modifier.align(Alignment.CenterHorizontally)) }
    }
}

/** Mirrors the attendance app's "Select your Sign-Out Location" list. Nothing is preselected. */
@Composable
private fun LocationDialog(
    title: String,
    options: List<String>,
    selected: String?,
    onDismissed: () -> Unit,
    onPick: (String?) -> Unit,
) {
    val p = LocalPalette.current
    // The tapped row highlights at once; the dialog then animates away with the new choice saved.
    var picked by remember { mutableStateOf(selected) }
    AppAlertDialog(
        onDismissed = onDismissed,
        title = title,
        buttons = { c ->
            if (selected != null) AppButton("Clear", { c.close { onPick(null) } }, kind = ButtonKind.Text)
            AppButton("Cancel", { c.close() }, kind = ButtonKind.Text)
        },
    ) { c ->
        options.forEach { loc ->
            val sel = loc == picked
            fun choose() = c.close {
                picked = loc
                onPick(loc)
            }
            Surface(
                onClick = ::choose,
                shape = FieldShape,
                color = animatedColor(if (sel) p.primarySoft else p.primarySofter),
                modifier = Modifier.fillMaxWidth().pressFeedback(pressedScale = 0.98f),
            ) {
                Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = sel, onClick = ::choose)
                    Text(loc, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
        Hint("These must match the attendance app's list exactly. Edit the list in Settings › Advanced if the attendance app changes it.")
    }
}

// ---- Schedule block -------------------------------------------------------------------------

private object WeekdaysOnly : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long) = !ScheduleCalculator.isWeekend(utcTimeMillis.toUtcDate())
}

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** "Schedule" heading, Recurring/Specific toggle, weekday chips, date range or specific dates. */
@Composable
fun ScheduleBlock(schedule: OperationSchedule, edit: ((OperationSchedule) -> OperationSchedule) -> Unit) {
    val p = LocalPalette.current
    var showAddDate by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FieldLabel("Schedule Dates", Modifier.weight(1f))
        }

        val modes = listOf(ScheduleMode.RECURRING_WEEKDAYS, ScheduleMode.SPECIFIC_DATES)
        SegmentedToggle(
            options = listOf("Recurring weekdays", "Specific dates"),
            selected = modes.indexOf(schedule.mode),
            onSelect = { i -> edit { it.copy(mode = modes[i]) } },
            icon = Icons.Outlined.CalendarMonth,
        )

        val reduce = LocalReduceMotion.current
        AnimatedContent(
            targetState = schedule.mode,
            transitionSpec = {
                fadeIn(Motion.tween(reduce, Motion.MEDIUM, delay = if (reduce) 0 else 60)) togetherWith
                    fadeOut(Motion.tween(reduce, Motion.SHORT)) using
                    SizeTransform(clip = false) { _, _ -> Motion.tween(reduce, Motion.MEDIUM, Motion.Standard) }
            },
            label = "scheduleMode",
        ) { mode ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (mode == ScheduleMode.RECURRING_WEEKDAYS) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { day ->
                    val weekend = day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                    val sel = !weekend && day in schedule.weekdays
                    val chip = animatedColor(
                        when {
                            sel -> p.primary
                            weekend -> p.disabledBg
                            else -> p.primarySofter
                        },
                    )
                    val label = animatedColor(
                        when {
                            sel -> p.onPrimary
                            weekend -> p.disabledText
                            else -> p.text
                        },
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .pressFeedback(enabled = !weekend, pressedScale = 0.92f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(chip)
                            .clickable(enabled = !weekend) {
                                edit { it.copy(weekdays = if (day in it.weekdays) it.weekdays - day else it.weekdays + day) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                            color = label,
                        )
                    }
                }
            }
            val hasRange = schedule.rangeStart != null || schedule.rangeEnd != null
            FieldBox(
                Icons.Outlined.CalendarMonth,
                if (!hasRange) "Date range: None (every week)"
                else "${schedule.rangeStart?.format(SHORT_DATE_FMT) ?: "…"} → ${schedule.rangeEnd?.format(SHORT_DATE_FMT) ?: "…"}",
                onClick = { showRange = true },
                trailing = {
                    AnimatedVisibility(
                        visible = hasRange,
                        enter = fadeIn(motionTween(Motion.SHORT)) + expandHorizontally(motionTween(Motion.MEDIUM)),
                        exit = fadeOut(motionTween(Motion.SHORT)) + shrinkHorizontally(motionTween(Motion.MEDIUM)),
                    ) {
                        AppButton("Clear", { edit { it.copy(rangeStart = null, rangeEnd = null) } }, kind = ButtonKind.Text)
                    }
                    AppButton("Set", { showRange = true }, kind = ButtonKind.Outlined)
                },
            )
        } else {
            val today = LocalDate.now()
            val upcomingDates = schedule.specificDates.count { !it.isBefore(today) }
            FieldBox(
                Icons.Outlined.EventAvailable,
                when (upcomingDates) {
                    0 -> "No dates selected"
                    1 -> "1 date selected"
                    else -> "$upcomingDates dates selected"
                },
                onClick = { showAddDate = true },
                placeholder = upcomingDates == 0,
                trailing = { AppButton("Choose dates", { showAddDate = true }, kind = ButtonKind.Outlined) },
            )
            FlowRow(
                Modifier.animateContentSize(motionTween(Motion.MEDIUM)),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                schedule.specificDates.sorted().forEach { d ->
                    val past = d.isBefore(today)
                    Surface(
                        onClick = { edit { it.copy(specificDates = it.specificDates - d) } },
                        shape = CircleShape,
                        color = if (past) p.disabledBg else p.primarySoft,
                        modifier = Modifier.pressFeedback(pressedScale = 0.92f),
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.format(SHORT_DATE_FMT), color = if (past) p.disabledText else p.text, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.Close, "Remove", tint = p.textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            ExpandFade(schedule.specificDates.any { it.isBefore(today) }) {
                AppButton(
                    "Remove past dates",
                    { edit { s -> s.copy(specificDates = s.specificDates.filterNot { it.isBefore(today) }.toSet()) } },
                    kind = ButtonKind.Text,
                )
            }
        }
        }
        }
    }

    if (showAddDate) {
        MultiDatePickerDialog(
            initial = schedule.specificDates,
            onDismissed = { showAddDate = false },
            onConfirm = { dates -> edit { it.copy(specificDates = dates) } },
        )
    }

    if (showRange) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = schedule.rangeStart?.toUtcMillis(),
            initialSelectedEndDateMillis = schedule.rangeEnd?.toUtcMillis(),
            selectableDates = WeekdaysOnly,
        )
        PickerDialog(
            onDismissed = { showRange = false },
            confirmLabel = "Save",
            confirmEnabled = state.selectedStartDateMillis != null,
            onConfirm = {
                val start = state.selectedStartDateMillis?.toUtcDate()
                val end = state.selectedEndDateMillis?.toUtcDate() ?: start
                edit { it.copy(rangeStart = start, rangeEnd = end) }
            },
        ) { DateRangePicker(state, Modifier.height(480.dp)) }
    }
}

/** "Check a date": explains whether a picked date would run. */
@Composable
private fun CheckDate(schedule: OperationSchedule) {
    val p = LocalPalette.current
    var show by remember { mutableStateOf(false) }
    var checkDate by rememberSaveable { mutableStateOf<String?>(null) }
    FieldBox(
        Icons.Outlined.EventAvailable, "Check a date", onClick = { show = true },
        trailing = { AppButton("Pick", { show = true }, kind = ButtonKind.Outlined) },
    )
    ExpandFade(checkDate != null, Modifier.fillMaxWidth()) {
        // A new result cross-fades over the previous one instead of the card jumping.
        FadeSwap(checkDate, Modifier.fillMaxWidth()) { value ->
            val d = value?.let(LocalDate::parse) ?: return@FadeSwap
            val decision = ScheduleCalculator.decide(schedule, d)
            val ok = decision == DayDecision.RUN
            Surface(shape = FieldShape, color = if (ok) p.greenSoft else p.dangerSoft, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${d.format(DATE_FMT)}: ${decision.description}",
                    Modifier.padding(12.dp),
                    color = if (ok) p.green else p.danger,
                )
            }
        }
    }
    if (show) {
        val state = rememberDatePickerState()
        PickerDialog(
            onDismissed = { show = false },
            confirmLabel = "Check",
            confirmEnabled = state.selectedDateMillis != null,
            onConfirm = { checkDate = state.selectedDateMillis?.toUtcDate()?.toString() },
        ) { DatePicker(state) }
    }
}

/** Material date / range picker inside the app's animated dialog, with Cancel and a confirm button. */
@Composable
private fun PickerDialog(
    onDismissed: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    picker: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    AppDialog(onDismissed, maxWidth = 400) { c ->
        Surface(shape = CardShape, color = p.card) {
            Column {
                picker()
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    AppButton("Cancel", { c.close() }, kind = ButtonKind.Text)
                    AppButton(confirmLabel, { c.close(onConfirm) }, enabled = confirmEnabled)
                }
            }
        }
    }
}
