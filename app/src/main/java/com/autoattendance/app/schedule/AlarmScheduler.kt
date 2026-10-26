package com.autoattendance.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.autoattendance.app.data.AutomationSettings
import com.autoattendance.app.data.Operation
import com.autoattendance.app.data.Repository
import com.autoattendance.app.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId

object AlarmScheduler {
    const val ACTION_RUN = "com.autoattendance.app.action.RUN"
    const val EXTRA_TRIGGER_AT = "trigger_at"
    const val EXTRA_OPERATION = "operation"

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    /** Next run of [op], or null when it (or the master switch) is off or no date matches. */
    fun nextRun(settings: AutomationSettings, op: Operation, after: LocalDateTime = LocalDateTime.now()) =
        settings.schedule(op).takeIf { settings.enabled && it.enabled }
            ?.let { ScheduleCalculator.nextRun(it, after) }

    /**
     * Cancels pending alarms and schedules the next eligible Sign In and Sign Out. Idempotent;
     * call it whenever a time, date, weekday or switch changes.
     */
    fun reschedule(context: Context): Map<Operation, LocalDateTime?> {
        val settings = Repository.get(context).settings.value
        return Operation.entries.associateWith { op -> schedule(context, op, nextRun(settings, op)) }
    }

    private fun schedule(context: Context, op: Operation, next: LocalDateTime?): LocalDateTime? {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(runIntent(context, op, 0L))
        if (next == null) return null

        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val operation = runIntent(context, op, millis)
        if (canScheduleExact(context)) {
            // setAlarmClock is exact and fires even in Doze; it shows the alarm icon in the status bar.
            val show = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), operation)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operation)
        }
        return next
    }

    /** One PendingIntent per operation (distinct request codes), so the two alarms never replace each other. */
    private fun runIntent(context: Context, op: Operation, triggerAt: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, 10 + op.ordinal,
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_RUN)
                .putExtra(EXTRA_OPERATION, op.name)
                .putExtra(EXTRA_TRIGGER_AT, triggerAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
