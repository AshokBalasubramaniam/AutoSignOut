package com.autoattendance.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autoattendance.app.automation.AttendanceAccessibilityService
import com.autoattendance.app.automation.RunMode
import com.autoattendance.app.automation.RunRequest
import com.autoattendance.app.data.AttemptLog
import com.autoattendance.app.data.AttemptResult
import com.autoattendance.app.data.Operation
import com.autoattendance.app.data.Repository
import com.autoattendance.app.data.Trigger
import com.autoattendance.app.feedback.Announcer
import com.autoattendance.app.feedback.Notifier
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_RUN) return
        val op = intent.getStringExtra(AlarmScheduler.EXTRA_OPERATION)
            ?.let { n -> Operation.entries.firstOrNull { it.name == n } } ?: return
        val repo = Repository.get(context)
        val triggerAt = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, System.currentTimeMillis())

        // Always queue the next eligible alarm first.
        AlarmScheduler.reschedule(context)

        val settings = repo.settings.value
        val schedule = settings.schedule(op)
        if (!settings.enabled || !schedule.enabled) return

        fun record(result: AttemptResult, message: String) = repo.saveAttempt(
            AttemptLog(
                id = UUID.randomUUID().toString(), operation = op, trigger = Trigger.SCHEDULED,
                scheduledAt = triggerAt, startedAt = System.currentTimeMillis(),
                finishedAt = System.currentTimeMillis(), location = schedule.location,
                previousStatus = repo.status.value.status, finalStatus = repo.status.value.status,
                result = result, message = message,
            ),
        )

        // Re-check the rules at fire time in case settings changed since scheduling.
        val date = Instant.ofEpochMilli(triggerAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val decision = ScheduleCalculator.decide(schedule, date)
        if (decision != DayDecision.RUN) {
            repo.info("Scheduled ${op.label} for $date skipped – ${decision.description}")
            record(AttemptResult.SKIPPED, decision.description)
            return
        }
        if (System.currentTimeMillis() - triggerAt > MAX_LATENESS_MS) {
            val msg = "The alarm fired too late (device off or alarm delayed). Please ${op.label.lowercase()} manually."
            repo.warn("Scheduled ${op.label} for $date was missed")
            record(AttemptResult.FAILED, msg)
            Notifier.failure(context, op, "Scheduled ${op.label} missed", msg)
            return
        }

        val service = AttendanceAccessibilityService.instance
        if (service == null) {
            val msg = "Accessibility service is OFF, so the attendance app could not be opened. Turn it on in " +
                "Settings › Accessibility › Auto Attendance, then ${op.label.lowercase()} manually."
            repo.error(msg)
            record(AttemptResult.FAILED, msg)
            Notifier.failure(context, op, "Automatic ${op.label} failed", msg)
            Announcer.failure(context, op)
            return
        }
        service.runner.enqueue(RunRequest(op, RunMode.SCHEDULED, scheduledAt = triggerAt))
    }

    private companion object {
        const val MAX_LATENESS_MS = 30 * 60 * 1000L
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for system broadcasts; ignore anything else another app might send.
        if (intent.action !in HANDLED_ACTIONS) return
        val next = AlarmScheduler.reschedule(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Repository.get(context).info(
                "Device restarted – schedule restored (next Sign In: ${next[Operation.SIGN_IN] ?: "none"}, " +
                    "next Sign Out: ${next[Operation.SIGN_OUT] ?: "none"})",
            )
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
