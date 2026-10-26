package com.autoattendance.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.autoattendance.app.automation.AttendanceAccessibilityService
import com.autoattendance.app.schedule.AlarmScheduler

data class LaunchableApp(val label: String, val packageName: String)

object SystemChecks {

    fun accessibilityEnabled(context: Context): Boolean {
        val me = ComponentName(context, AttendanceAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun notificationsEnabled(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun exactAlarmsAllowed(context: Context) = AlarmScheduler.canScheduleExact(context)

    fun batteryUnrestricted(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun isInstalled(context: Context, pkg: String?) =
        pkg != null && context.packageManager.getLaunchIntentForPackage(pkg) != null

    fun launchableApps(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<LaunchableApp> { looksLikeAttendanceApp(it) }.thenBy { it.label.lowercase() })
    }

    fun looksLikeAttendanceApp(app: LaunchableApp) =
        app.label.contains("attendance", ignoreCase = true) || app.packageName.contains("attendance", ignoreCase = true)

    fun openAccessibilitySettings(context: Context) =
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openAppDetails(context: Context) = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    fun openNotificationSettings(context: Context) = context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
            )
        }
    }

    @android.annotation.SuppressLint("BatteryLife")
    fun requestBatteryExemption(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(direct) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}
