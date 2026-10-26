package com.autoattendance.app

import com.autoattendance.app.data.OperationSchedule
import com.autoattendance.app.data.ScheduleMode
import com.autoattendance.app.schedule.DayDecision
import com.autoattendance.app.schedule.ScheduleCalculator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduleCalculatorTest {

    // 2026-10-23 is a Friday.
    private val friday = LocalDate.of(2026, 10, 23)
    private val saturday = friday.plusDays(1)
    private val sunday = friday.plusDays(2)
    private val monday = friday.plusDays(3)
    private val base = OperationSchedule(enabled = true, hour = 21, minute = 27)

    @Test
    fun `weekends are always skipped even if selected`() {
        val s = base.copy(weekdays = DayOfWeek.entries.toSet())
        assertEquals(DayDecision.WEEKEND, ScheduleCalculator.decide(s, saturday))
        assertEquals(DayDecision.WEEKEND, ScheduleCalculator.decide(s, sunday))
        val specific = base.copy(mode = ScheduleMode.SPECIFIC_DATES, specificDates = setOf(saturday))
        assertEquals(DayDecision.WEEKEND, ScheduleCalculator.decide(specific, saturday))
    }

    @Test
    fun `unselected weekday is skipped`() {
        val s = base.copy(weekdays = setOf(DayOfWeek.MONDAY))
        assertEquals(DayDecision.WEEKDAY_NOT_SELECTED, ScheduleCalculator.decide(s, friday))
        assertEquals(DayDecision.RUN, ScheduleCalculator.decide(s, monday))
    }

    @Test
    fun `dates outside the range are skipped`() {
        val s = base.copy(rangeStart = monday, rangeEnd = monday.plusDays(4))
        assertEquals(DayDecision.OUTSIDE_RANGE, ScheduleCalculator.decide(s, friday))
        assertEquals(DayDecision.RUN, ScheduleCalculator.decide(s, monday))
        assertEquals(DayDecision.OUTSIDE_RANGE, ScheduleCalculator.decide(s, monday.plusDays(7)))
    }

    @Test
    fun `specific dates mode only runs on chosen dates`() {
        val s = base.copy(mode = ScheduleMode.SPECIFIC_DATES, specificDates = setOf(monday))
        assertEquals(DayDecision.DATE_NOT_SELECTED, ScheduleCalculator.decide(s, friday))
        assertEquals(DayDecision.RUN, ScheduleCalculator.decide(s, monday))
    }

    @Test
    fun `next run is today when the time has not passed`() {
        assertEquals(friday.atTime(21, 27), ScheduleCalculator.nextRun(base, friday.atTime(18, 0)))
    }

    @Test
    fun `next run skips the weekend once today's time has passed`() {
        assertEquals(monday.atTime(21, 27), ScheduleCalculator.nextRun(base, friday.atTime(21, 27)))
    }

    @Test
    fun `no next run after the range ends`() {
        val s = base.copy(rangeStart = friday, rangeEnd = friday)
        assertNull(ScheduleCalculator.nextRun(s, LocalDateTime.of(friday, java.time.LocalTime.of(22, 0))))
    }

    @Test
    fun `sign in and sign out schedules are independent`() {
        val signIn = OperationSchedule(enabled = true, hour = 9, minute = 27)
        val signOut = OperationSchedule(enabled = true, hour = 19, minute = 0, weekdays = setOf(DayOfWeek.MONDAY))
        val now = friday.atTime(8, 0)
        assertEquals(friday.atTime(9, 27), ScheduleCalculator.nextRun(signIn, now))
        assertEquals(monday.atTime(19, 0), ScheduleCalculator.nextRun(signOut, now))
    }
}
