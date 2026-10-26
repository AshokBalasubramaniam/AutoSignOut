package com.autoattendance.app.schedule

import com.autoattendance.app.data.OperationSchedule
import com.autoattendance.app.data.ScheduleMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

enum class DayDecision(val description: String) {
    RUN("Will run on this date"),
    WEEKEND("Skipped: Saturday and Sunday are always excluded"),
    WEEKDAY_NOT_SELECTED("Skipped: this weekday is not selected"),
    OUTSIDE_RANGE("Skipped: date is outside the selected date range"),
    DATE_NOT_SELECTED("Skipped: date is not one of the selected dates"),
}

/** Pure scheduling rules – no Android dependencies, covered by unit tests. */
object ScheduleCalculator {

    fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    fun decide(schedule: OperationSchedule, date: LocalDate): DayDecision {
        if (isWeekend(date)) return DayDecision.WEEKEND
        return when (schedule.mode) {
            ScheduleMode.SPECIFIC_DATES ->
                if (date in schedule.specificDates) DayDecision.RUN else DayDecision.DATE_NOT_SELECTED

            ScheduleMode.RECURRING_WEEKDAYS -> when {
                date.dayOfWeek !in schedule.weekdays -> DayDecision.WEEKDAY_NOT_SELECTED
                schedule.rangeStart != null && date.isBefore(schedule.rangeStart) -> DayDecision.OUTSIDE_RANGE
                schedule.rangeEnd != null && date.isAfter(schedule.rangeEnd) -> DayDecision.OUTSIDE_RANGE
                else -> DayDecision.RUN
            }
        }
    }

    /** First scheduled run strictly after [after], or null if none within [horizonDays]. */
    fun nextRun(schedule: OperationSchedule, after: LocalDateTime, horizonDays: Int = 800): LocalDateTime? =
        upcoming(schedule, after, 1, horizonDays).firstOrNull()

    fun upcoming(
        schedule: OperationSchedule,
        after: LocalDateTime,
        count: Int,
        horizonDays: Int = 800,
    ): List<LocalDateTime> {
        val result = mutableListOf<LocalDateTime>()
        var date = after.toLocalDate()
        repeat(horizonDays) {
            val at = date.atTime(schedule.hour, schedule.minute)
            if (at.isAfter(after) && decide(schedule, date) == DayDecision.RUN) {
                result += at
                if (result.size == count) return result
            }
            date = date.plusDays(1)
        }
        return result
    }
}
