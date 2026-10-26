package com.autoattendance.app.feedback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.autoattendance.app.R
import com.autoattendance.app.automation.WakeActivity
import com.autoattendance.app.data.Operation
import com.autoattendance.app.ui.MainActivity

object Notifier {
    private const val CH_STATUS = "status"
    private const val CH_ALERT = "alerts"
    private const val CH_ACTION = "action_required"

    private const val ID_UNLOCK = 101
    private const val ID_AUTH = 102

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_STATUS, "Attendance results", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_ALERT, "Automation errors", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_ACTION, "Action required (unlock / log in)", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    /** Sign In and Sign Out results use separate ids, so one never hides the other. */
    private fun resultId(op: Operation?) = 110 + (op?.ordinal ?: 9)

    fun success(context: Context, op: Operation, title: String, text: String) =
        post(context, resultId(op), CH_STATUS, title, text, openApp(context))

    fun info(context: Context, op: Operation?, title: String, text: String) =
        post(context, resultId(op), CH_STATUS, title, text, openApp(context))

    fun failure(context: Context, op: Operation?, title: String, text: String) =
        post(context, resultId(op), CH_ALERT, title, text, openApp(context))

    fun unlockRequired(context: Context, op: Operation?, minutes: Int) {
        val unlock = PendingIntent.getActivity(
            context, 2,
            Intent(context, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val action = op?.label ?: "check the attendance app"
        post(
            context, ID_UNLOCK, CH_ACTION,
            "Unlock your phone to $action",
            "The automatic $action is waiting for you to unlock the phone (up to $minutes min).",
            unlock, ongoing = true,
        )
    }

    fun cancelUnlock(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(ID_UNLOCK)

    /** The attendance app needs the user to log in. Tapping opens the attendance app; nothing is ever typed for them. */
    fun authRequired(context: Context, op: Operation?, targetPackage: String?) {
        val launch = targetPackage?.let { context.packageManager.getLaunchIntentForPackage(it) }
        val intent = launch?.let {
            PendingIntent.getActivity(
                context, 4, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } ?: openApp(context)
        post(
            context, ID_AUTH, CH_ACTION,
            "Log in to the attendance app" + (op?.let { " to ${it.label}" } ?: ""),
            "The attendance app is asking you to log in. Open the attendance app and complete the login yourself – this app never " +
                "enters or stores your username, password or OTP. Then ${op?.label?.lowercase() ?: "retry"} manually.",
            intent,
        )
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 3, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(
        context: Context, id: Int, channel: String, title: String, text: String,
        intent: PendingIntent, ongoing: Boolean = false,
    ) {
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setCategory(if (ongoing) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            // On a locked screen only the title is shown; details (location, screen text) need an unlock.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_stat_clock)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.app_name))
                    .build(),
            )
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the event is still in the in-app log.
        }
    }
}
