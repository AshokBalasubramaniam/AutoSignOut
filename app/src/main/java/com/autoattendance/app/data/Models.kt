package com.autoattendance.app.data

import java.time.DayOfWeek
import java.time.LocalDate

enum class ScheduleMode { RECURRING_WEEKDAYS, SPECIFIC_DATES }

enum class AttendanceStatus(val label: String) {
    SIGNED_IN("Signed In"),
    SIGNED_OUT("Signed Out"),
    UNKNOWN("Unknown"),
}

enum class Operation(val label: String) {
    SIGN_IN("Sign In"),
    SIGN_OUT("Sign Out");

    /** Status the attendance app must show before this operation may run (Sign In needs Signed Out, and vice versa). */
    val requiredStatus: AttendanceStatus
        get() = if (this == SIGN_IN) AttendanceStatus.SIGNED_OUT else AttendanceStatus.SIGNED_IN

    /** Status the attendance app must show afterwards for the operation to count as done. */
    val resultStatus: AttendanceStatus
        get() = if (this == SIGN_IN) AttendanceStatus.SIGNED_IN else AttendanceStatus.SIGNED_OUT

    val successPhrase: String
        get() = if (this == SIGN_IN) "Signed in successfully" else "Signed out successfully"
}

/** Schedule, location and remarks for one operation. Sign In and Sign Out each have their own. */
data class OperationSchedule(
    val enabled: Boolean = false,
    val hour: Int,
    val minute: Int,
    val mode: ScheduleMode = ScheduleMode.RECURRING_WEEKDAYS,
    /** Only Monday–Friday are ever honoured; weekends are always excluded. */
    val weekdays: Set<DayOfWeek> = AutomationSettings.WORKDAYS,
    /** Optional limit for recurring mode. Both null = no limit. */
    val rangeStart: LocalDate? = null,
    val rangeEnd: LocalDate? = null,
    val specificDates: Set<LocalDate> = emptySet(),
    /** Location to pick from the attendance app's list. Null = not chosen yet; the run then stops instead of picking one itself. */
    val location: String? = null,
    /** Typed into the attendance app's Remarks field only when not blank. */
    val remarks: String = "",
)

data class AutomationSettings(
    /** Master switch. When off, neither operation is scheduled. */
    val enabled: Boolean = false,
    val signIn: OperationSchedule = OperationSchedule(hour = 9, minute = 27),
    val signOut: OperationSchedule = OperationSchedule(hour = 19, minute = 0),
    val targetPackage: String? = null,
    val targetAppName: String? = null,
    /** Exact text/label of the attendance buttons. Only the one for the running operation is clicked. */
    val signOutLabel: String = "Sign Out",
    val signInLabel: String = "Sign In",
    /** Texts that prove the attendance app's home/attendance screen is showing (comma separated). */
    val homeMarkers: String = "Time elapsed, Shift, Hello, Regularize",
    /** Choices offered in this app's location dialog (comma separated), matching the attendance app's list. */
    val locationOptions: String = DEFAULT_LOCATIONS.joinToString(", "),
    /** Allow clicking Yes/Confirm on an "Are you sure?" dialog. */
    val allowConfirmDialog: Boolean = false,
    /** How long to wait for the user to unlock the phone before giving up. */
    val unlockWaitMinutes: Int = 30,
    val voiceEnabled: Boolean = true,
) {
    val homeMarkerList: List<String>
        get() = homeMarkers.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val locationOptionList: List<String>
        get() = locationOptions.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun schedule(op: Operation) = if (op == Operation.SIGN_IN) signIn else signOut

    fun withSchedule(op: Operation, s: OperationSchedule) =
        if (op == Operation.SIGN_IN) copy(signIn = s) else copy(signOut = s)

    fun buttonLabel(op: Operation) = if (op == Operation.SIGN_IN) signInLabel else signOutLabel

    companion object {
        val WORKDAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        val DEFAULT_LOCATIONS = listOf("Work from Home", "Office", "Client Location", "On-Duty")
    }
}

data class StatusInfo(
    val status: AttendanceStatus = AttendanceStatus.UNKNOWN,
    /** When [status] was last confirmed on the attendance app's screen (epoch millis, 0 = never). */
    val observedAt: Long = 0L,
    /** Last verified automatic Sign In / Sign Out. */
    val lastAction: Operation? = null,
    val lastActionAt: Long = 0L,
    val lastActionLocation: String? = null,
    val lastAnnouncedStatus: AttendanceStatus? = null,
    val lastAnnouncedDate: String? = null,
)

enum class LogLevel { INFO, WARN, ERROR }

/** Step-by-step diagnostic event (the "Diagnostics" tab). Attempts themselves are in Room. */
data class LogEntry(val time: Long, val level: LogLevel, val message: String)
