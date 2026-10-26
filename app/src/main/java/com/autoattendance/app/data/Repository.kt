package com.autoattendance.app.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.util.Log
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth: settings and status in SharedPreferences, attempt logs in Room.
 * Never stores attendance-app credentials, OTPs or tokens.
 */
class Repository private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("auto_attendance", Context.MODE_PRIVATE)
    private val db = AttemptDatabase.create(context)

    /** Step logs go to logcat only in debug builds; release builds keep them in the app only. */
    private val logToLogcat = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** One writer thread keeps attempt inserts/updates in order without blocking the caller. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AutomationSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow(loadStatus())
    val status: StateFlow<StatusInfo> = _status.asStateFlow()

    private val _log = MutableStateFlow(loadLog())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    val attempts: Flow<List<AttemptLog>> = db.attempts().observeRecent()

    /** Live progress of the current run (not persisted). Null when idle. */
    val runProgress = MutableStateFlow<String?>(null)

    /** Result of the last test run (not persisted). */
    val testResult = MutableStateFlow<String?>(null)

    @Synchronized
    fun updateSettings(transform: (AutomationSettings) -> AutomationSettings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putBoolean(K_MASTER, s.enabled)
            .putSchedule(P_IN, s.signIn)
            .putSchedule(P_OUT, s.signOut)
            .putString(K_PACKAGE, s.targetPackage)
            .putString(K_APP_NAME, s.targetAppName)
            .putString(K_SIGN_OUT_LABEL, s.signOutLabel)
            .putString(K_SIGN_IN_LABEL, s.signInLabel)
            .putString(K_HOME_MARKERS, s.homeMarkers)
            .putString(K_LOCATIONS, s.locationOptions)
            .putBoolean(K_ALLOW_CONFIRM, s.allowConfirmDialog)
            .putInt(K_UNLOCK_WAIT, s.unlockWaitMinutes)
            .putBoolean(K_VOICE, s.voiceEnabled)
            .apply()
        _settings.value = s
    }

    fun updateSchedule(op: Operation, transform: (OperationSchedule) -> OperationSchedule) =
        updateSettings { it.withSchedule(op, transform(it.schedule(op))) }

    @Synchronized
    fun recordStatus(status: AttendanceStatus, at: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(K_STATUS, status.name)
            .putLong(K_STATUS_AT, at)
            .apply()
        _status.value = _status.value.copy(status = status, observedAt = at)
    }

    /** A verified automatic Sign In / Sign Out. */
    @Synchronized
    fun recordAction(op: Operation, location: String?, at: Long) {
        prefs.edit()
            .putString(K_STATUS, op.resultStatus.name)
            .putLong(K_STATUS_AT, at)
            .putString(K_LAST_ACTION, op.name)
            .putLong(K_LAST_ACTION_AT, at)
            .putString(K_LAST_ACTION_LOCATION, location)
            .apply()
        _status.value = _status.value.copy(
            status = op.resultStatus, observedAt = at,
            lastAction = op, lastActionAt = at, lastActionLocation = location,
        )
    }

    /** Returns false if [status] was already announced today (so callers stay silent). */
    @Synchronized
    fun tryMarkAnnounced(status: AttendanceStatus, date: LocalDate = LocalDate.now()): Boolean {
        val cur = _status.value
        if (cur.lastAnnouncedStatus == status && cur.lastAnnouncedDate == date.toString()) return false
        prefs.edit()
            .putString(K_ANNOUNCED_STATUS, status.name)
            .putString(K_ANNOUNCED_DATE, date.toString())
            .apply()
        _status.value = cur.copy(lastAnnouncedStatus = status, lastAnnouncedDate = date.toString())
        return true
    }

    /** Inserts or updates an attempt row. Fire-and-forget; safe to call from the main thread. */
    fun saveAttempt(attempt: AttemptLog) {
        io.launch {
            db.attempts().upsert(attempt)
            db.attempts().prune(MAX_ATTEMPTS)
        }
    }

    @Synchronized
    fun log(level: LogLevel, message: String) {
        if (logToLogcat) {
            Log.println(
                when (level) {
                    LogLevel.INFO -> Log.INFO
                    LogLevel.WARN -> Log.WARN
                    LogLevel.ERROR -> Log.ERROR
                },
                TAG, message,
            )
        }
        val entries = (listOf(LogEntry(System.currentTimeMillis(), level, message)) + _log.value)
            .take(MAX_LOG)
        val arr = JSONArray()
        entries.forEach {
            arr.put(JSONObject().put("t", it.time).put("l", it.level.name).put("m", it.message))
        }
        prefs.edit().putString(K_LOG, arr.toString()).apply()
        _log.value = entries
    }

    fun info(message: String) = log(LogLevel.INFO, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun error(message: String) = log(LogLevel.ERROR, message)

    /** Clears both the attempt history and the diagnostic events. */
    @Synchronized
    fun clearLogs() {
        prefs.edit().remove(K_LOG).apply()
        _log.value = emptyList()
        io.launch { db.attempts().clear() }
    }

    // ---- Persistence ------------------------------------------------------------------------

    private fun SharedPreferences.Editor.putSchedule(p: String, s: OperationSchedule) = apply {
        putBoolean(p + K_ENABLED, s.enabled)
        putInt(p + K_HOUR, s.hour)
        putInt(p + K_MINUTE, s.minute)
        putString(p + K_MODE, s.mode.name)
        putString(p + K_WEEKDAYS, s.weekdays.joinToString(",") { it.value.toString() })
        putString(p + K_RANGE_START, s.rangeStart?.toString())
        putString(p + K_RANGE_END, s.rangeEnd?.toString())
        putString(p + K_DATES, s.specificDates.sorted().joinToString(","))
        putString(p + K_LOCATION, s.location)
        putString(p + K_REMARKS, s.remarks)
    }

    private fun loadSchedule(p: String, d: OperationSchedule): OperationSchedule {
        fun k(name: String) = p + name
        return OperationSchedule(
            enabled = prefs.getBoolean(k(K_ENABLED), d.enabled),
            hour = prefs.getInt(k(K_HOUR), d.hour),
            minute = prefs.getInt(k(K_MINUTE), d.minute),
            mode = enumOrNull<ScheduleMode>(prefs.getString(k(K_MODE), null)) ?: d.mode,
            weekdays = prefs.getString(k(K_WEEKDAYS), null)?.let { str ->
                str.split(',').mapNotNull { it.toIntOrNull()?.let(DayOfWeek::of) }.toSet()
            } ?: d.weekdays,
            rangeStart = prefs.getString(k(K_RANGE_START), null)?.let(LocalDate::parse),
            rangeEnd = prefs.getString(k(K_RANGE_END), null)?.let(LocalDate::parse),
            specificDates = prefs.getString(k(K_DATES), null)
                ?.split(',')?.filter { it.isNotBlank() }?.map(LocalDate::parse)?.toSet()
                ?: emptySet(),
            location = prefs.getString(p + K_LOCATION, null),
            remarks = prefs.getString(p + K_REMARKS, null) ?: "",
        )
    }

    private fun loadSettings(): AutomationSettings {
        val d = AutomationSettings()
        return AutomationSettings(
            enabled = prefs.getBoolean(K_MASTER, d.enabled),
            signIn = loadSchedule(P_IN, d.signIn),
            signOut = loadSchedule(P_OUT, d.signOut),
            targetPackage = prefs.getString(K_PACKAGE, null),
            targetAppName = prefs.getString(K_APP_NAME, null),
            signOutLabel = prefs.getString(K_SIGN_OUT_LABEL, null) ?: d.signOutLabel,
            signInLabel = prefs.getString(K_SIGN_IN_LABEL, null) ?: d.signInLabel,
            homeMarkers = prefs.getString(K_HOME_MARKERS, null) ?: d.homeMarkers,
            locationOptions = prefs.getString(K_LOCATIONS, null) ?: d.locationOptions,
            allowConfirmDialog = prefs.getBoolean(K_ALLOW_CONFIRM, d.allowConfirmDialog),
            unlockWaitMinutes = prefs.getInt(K_UNLOCK_WAIT, d.unlockWaitMinutes),
            voiceEnabled = prefs.getBoolean(K_VOICE, d.voiceEnabled),
        )
    }

    private fun loadStatus() = StatusInfo(
        status = enumOrNull<AttendanceStatus>(prefs.getString(K_STATUS, null)) ?: AttendanceStatus.UNKNOWN,
        observedAt = prefs.getLong(K_STATUS_AT, 0L),
        lastAction = enumOrNull<Operation>(prefs.getString(K_LAST_ACTION, null)),
        lastActionAt = prefs.getLong(K_LAST_ACTION_AT, 0L),
        lastActionLocation = prefs.getString(K_LAST_ACTION_LOCATION, null),
        lastAnnouncedStatus = enumOrNull<AttendanceStatus>(prefs.getString(K_ANNOUNCED_STATUS, null)),
        lastAnnouncedDate = prefs.getString(K_ANNOUNCED_DATE, null),
    )

    private fun loadLog(): List<LogEntry> = runCatching {
        val arr = JSONArray(prefs.getString(K_LOG, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LogEntry(o.getLong("t"), LogLevel.valueOf(o.getString("l")), o.getString("m"))
        }
    }.getOrDefault(emptyList())

    companion object {
        const val TAG = "AutoAttendance"
        private const val MAX_LOG = 300
        private const val MAX_ATTEMPTS = 500

        private const val P_IN = "in_"
        private const val P_OUT = "out_"

        private const val K_MASTER = "master_enabled"
        private const val K_ENABLED = "enabled"
        private const val K_HOUR = "hour"
        private const val K_MINUTE = "minute"
        private const val K_MODE = "mode"
        private const val K_WEEKDAYS = "weekdays"
        private const val K_RANGE_START = "range_start"
        private const val K_RANGE_END = "range_end"
        private const val K_DATES = "dates"
        private const val K_LOCATION = "location"
        private const val K_REMARKS = "remarks"
        private const val K_PACKAGE = "target_package"
        private const val K_APP_NAME = "target_app_name"
        private const val K_SIGN_OUT_LABEL = "sign_out_label"
        private const val K_SIGN_IN_LABEL = "sign_in_label"
        private const val K_HOME_MARKERS = "home_markers"
        private const val K_LOCATIONS = "location_options"
        private const val K_ALLOW_CONFIRM = "allow_confirm"
        private const val K_UNLOCK_WAIT = "unlock_wait"
        private const val K_VOICE = "voice"
        private const val K_STATUS = "status"
        private const val K_STATUS_AT = "status_at"
        private const val K_LAST_ACTION = "last_action"
        private const val K_LAST_ACTION_AT = "last_action_at"
        private const val K_LAST_ACTION_LOCATION = "last_action_location"
        private const val K_ANNOUNCED_STATUS = "announced_status"
        private const val K_ANNOUNCED_DATE = "announced_date"
        private const val K_LOG = "log"

        private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }

        @Volatile
        private var instance: Repository? = null

        fun get(context: Context): Repository =
            instance ?: synchronized(this) {
                instance ?: Repository(context.applicationContext).also { instance = it }
            }
    }
}
