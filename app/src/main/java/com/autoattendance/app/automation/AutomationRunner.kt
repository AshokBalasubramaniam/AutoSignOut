package com.autoattendance.app.automation

import android.app.KeyguardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.autoattendance.app.data.AttemptLog
import com.autoattendance.app.data.AttemptResult
import com.autoattendance.app.data.AttendanceStatus
import com.autoattendance.app.data.LogLevel
import com.autoattendance.app.data.Operation
import com.autoattendance.app.data.OperationSchedule
import com.autoattendance.app.data.Repository
import com.autoattendance.app.data.Trigger
import com.autoattendance.app.feedback.Announcer
import com.autoattendance.app.feedback.Notifier
import com.autoattendance.app.ui.MainActivity
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class RunMode(val label: String) {
    SCHEDULED("Scheduled"),
    /** Reads the home screen only. Never clicks anything. */
    TEST_DETECT("Detection test"),
    /** Opens the form and selects the location, but never presses the final Sign In / Sign Out. */
    DRY_RUN("Dry run"),
    TEST_EXECUTE("Test execution"),
}

/** [op] is null only for [RunMode.TEST_DETECT]. */
data class RunRequest(val op: Operation?, val mode: RunMode, val scheduledAt: Long? = null)

/**
 * One Sign In or Sign Out attempt, as a main-thread state machine:
 * network → unlock → open the attendance app → verify status on home → open the Details form → pick the configured
 * location → verify it → remarks → press the button → verify the new status on home.
 * Only the button for the running operation is ever pressed. Any doubt ends the run without submitting.
 */
class AutomationRunner(private val service: AttendanceAccessibilityService) {

    private enum class Phase { IDLE, WAIT_NETWORK, WAIT_UNLOCK, FIND_HOME, OPEN_FORM, PICK_LOCATION, FILL_FORM, VERIFY }

    private val repo = Repository.get(service)
    private val handler = Handler(Looper.getMainLooper())
    private val power = service.getSystemService(PowerManager::class.java)
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val queue = ArrayDeque<RunRequest>()

    private var phase = Phase.IDLE
    private var request = RunRequest(null, RunMode.TEST_DETECT)
    private var labels = Labels("", "", emptyList())
    private var config: OperationSchedule? = null
    private var attempt: AttemptLog? = null
    private var deadline = 0L
    private var networkAttempts = 0
    private var lastKey: Pair<ScreenState, Operation?>? = null
    private var sameStateCount = 0
    private var backPresses = 0
    private var locationTries = 0
    private var remarksTries = 0
    private var confirmClicked = false
    private var executedAt: Long? = null
    private var locationVerified = false
    /** Set when a step has already scheduled the next poll (with a new phase and timeout). */
    private var pollQueued = false
    private var previousStatus = AttendanceStatus.UNKNOWN
    private var observedStatus = AttendanceStatus.UNKNOWN
    /** Non-fatal remark added to the result (e.g. the attendance app had no Remarks field). */
    private var note: String? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null

    private val pollRunnable = Runnable { poll() }
    private val unlockRunnable = Runnable { checkUnlocked() }
    private val networkRunnable = Runnable { stepNetwork() }

    val isRunning get() = phase != Phase.IDLE

    private val title get() = listOfNotNull(request.mode.label, request.op?.label).joinToString(" ")

    /** For alarms: runs now, or after the current run if one is in progress (e.g. both times are equal). */
    fun enqueue(req: RunRequest) {
        if (!isRunning) {
            start(req)
            return
        }
        if (queue.none { it.op == req.op && it.mode == req.mode }) queue.addLast(req)
        repo.info("${req.mode.label} ${req.op?.label} queued – another run is in progress")
    }

    fun start(req: RunRequest): Boolean {
        if (isRunning) {
            repo.warn("${req.mode.label} ${req.op?.label ?: ""} ignored – another run is already in progress")
            return false
        }
        val settings = repo.settings.value
        request = req
        labels = Labels(settings.signInLabel, settings.signOutLabel, settings.homeMarkerList)
        config = req.op?.let(settings::schedule)
        networkAttempts = 0
        backPresses = 0
        locationTries = 0
        remarksTries = 0
        confirmClicked = false
        executedAt = null
        locationVerified = false
        previousStatus = AttendanceStatus.UNKNOWN
        observedStatus = AttendanceStatus.UNKNOWN
        note = null
        phase = Phase.WAIT_NETWORK
        if (req.mode != RunMode.SCHEDULED) repo.testResult.value = null
        attempt = req.op?.let { op ->
            AttemptLog(
                id = UUID.randomUUID().toString(), operation = op,
                trigger = when (req.mode) {
                    RunMode.DRY_RUN -> Trigger.DRY_RUN
                    RunMode.TEST_EXECUTE -> Trigger.TEST_EXECUTE
                    else -> Trigger.SCHEDULED
                },
                scheduledAt = req.scheduledAt, startedAt = System.currentTimeMillis(),
                location = config?.location,
            ).also(repo::saveAttempt)
        }
        repo.info("$title started" + (config?.location?.let { " (location: $it)" } ?: ""))
        cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoattendance:run").apply {
            acquire(RUN_MAX_MS)
        }

        val pkg = settings.targetPackage
        when {
            pkg == null -> fail("Attendance app is not selected. Choose it under Setup.")
            service.packageManager.getLaunchIntentForPackage(pkg) == null ->
                fail("Attendance app ($pkg) is not installed or cannot be opened.")
            req.op != null && config?.location.isNullOrBlank() ->
                fail("No ${req.op.label} location is selected. Choose one in this app – the automation never picks a location by itself.")
            else -> stepNetwork()
        }
        return true
    }

    fun cancel(reason: String) {
        if (isRunning) fail(reason)
    }

    fun onUserPresent() {
        if (phase != Phase.WAIT_UNLOCK) return
        handler.removeCallbacks(unlockRunnable)
        checkUnlocked()
    }

    // ---- Preparation -------------------------------------------------------------------------

    private fun stepNetwork() {
        if (hasNetwork()) {
            stepUnlock()
            return
        }
        networkAttempts++
        if (request.mode != RunMode.SCHEDULED || networkAttempts > NETWORK_RETRIES) {
            fail("No internet connection. The attendance app cannot record attendance offline – please ${actionLower()} manually when online.")
            return
        }
        progress("No network – retry $networkAttempts of $NETWORK_RETRIES in 30 s")
        repo.warn("No network connection, retrying ($networkAttempts/$NETWORK_RETRIES)")
        handler.postDelayed(networkRunnable, NETWORK_RETRY_MS)
    }

    private fun stepUnlock() {
        if (isUnlocked()) {
            stepOpenTargetApp()
            return
        }
        // We never bypass a secure lock: the system shows its own unlock prompt and the user decides.
        val minutes = repo.settings.value.unlockWaitMinutes
        phase = Phase.WAIT_UNLOCK
        deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
        progress("Phone is locked – waiting up to $minutes min for unlock")
        repo.info("Phone is locked – showing unlock prompt and waiting up to $minutes min")
        Notifier.unlockRequired(service, request.op, minutes)
        service.startActivity(Intent(service, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        handler.postDelayed(unlockRunnable, 1000)
    }

    private fun checkUnlocked() {
        if (phase != Phase.WAIT_UNLOCK) return
        when {
            isUnlocked() -> {
                Notifier.cancelUnlock(service)
                repo.info("Phone unlocked – continuing")
                stepOpenTargetApp()
            }
            SystemClock.elapsedRealtime() > deadline ->
                fail("The phone stayed locked, so the attendance app could not be opened. Please ${actionLower()} manually.")
            else -> handler.postDelayed(unlockRunnable, 1000)
        }
    }

    private fun stepOpenTargetApp() {
        val pkg = repo.settings.value.targetPackage ?: return fail("Attendance app is not selected.")
        @Suppress("DEPRECATION")
        screenLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "autoattendance:screen",
        ).apply { acquire(SCREEN_MAX_MS) }

        if (service.foregroundPackage() == pkg) {
            repo.info("The attendance app is already open – not relaunching")
        } else {
            val launch = service.packageManager.getLaunchIntentForPackage(pkg)
                ?: return fail("Attendance app ($pkg) cannot be opened.")
            service.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            repo.info("Opened the attendance app")
        }
        enter(Phase.FIND_HOME, FIND_TIMEOUT_MS, "Waiting for the attendance app's home screen…", firstPollMs = 1500)
    }

    // ---- Screen polling ----------------------------------------------------------------------

    private fun poll() {
        pollQueued = false
        val a = service.analyzeTarget(labels)
        val key = a?.let { it.state to it.form }
        if (key == lastKey) sameStateCount++ else {
            lastKey = key
            sameStateCount = 1
        }
        val op = request.op
        when (phase) {
            Phase.FIND_HOME -> onFindHome(a)
            Phase.OPEN_FORM -> onOpenForm(a, op ?: return)
            Phase.PICK_LOCATION -> onPickLocation(a, op ?: return)
            Phase.FILL_FORM -> onFillForm(a, op ?: return)
            Phase.VERIFY -> onVerify(a, op ?: return)
            else -> return
        }
        if (phase == Phase.IDLE || pollQueued) return
        if (SystemClock.elapsedRealtime() > deadline) {
            if (phase == Phase.VERIFY) {
                observedStatus = AttendanceStatus.UNKNOWN
                repo.recordStatus(AttendanceStatus.UNKNOWN)
            }
            fail(timeoutMessage(a))
        } else {
            handler.postDelayed(pollRunnable, POLL_MS)
        }
    }

    private fun onFindHome(a: ScreenAnalysis?) {
        when (a?.state) {
            ScreenState.HOME_SIGNED_IN, ScreenState.HOME_SIGNED_OUT -> if (sameStateCount >= 2) {
                val status = homeStatus(a)
                repo.recordStatus(status)
                previousStatus = status
                observedStatus = status
                val op = request.op
                if (op == null) {
                    val label = if (status == AttendanceStatus.SIGNED_IN) labels.signOut else labels.signIn
                    return finish(
                        LogLevel.INFO,
                        "✅ You are logged in to the attendance app. Status: ${status.label}. The \"$label\" button was " +
                            "identified. No click was performed (detection test only).",
                        AttemptResult.SUCCESS,
                    )
                }
                if (status == op.resultStatus) return skip("Already ${status.label.lowercase()} – no ${op.label} performed.")

                val button = a.actionButton
                if (button == null || !ScreenAnalyzer.isButtonFor(button, op, labels)) {
                    return fail("The ${op.label} button could not be identified safely. Nothing was clicked.")
                }
                if (!service.click(button)) return fail("The ${op.label} button could not be clicked.")
                repo.info("Clicked \"${labels.of(op)}\" on the attendance app's home screen")
                enter(Phase.OPEN_FORM, FORM_TIMEOUT_MS, "Waiting for the ${op.label} Details screen…")
            }

            // Left over from an earlier visit: go back to the home screen, where the status can be verified.
            ScreenState.DETAILS, ScreenState.LOCATION_DIALOG -> if (sameStateCount >= 2) {
                if (backPresses >= MAX_BACK_PRESSES) {
                    return fail("The attendance app stayed on a form after pressing Back $MAX_BACK_PRESSES times. Screen: ${a.summary()}")
                }
                backPresses++
                service.back()
                repo.info("The attendance app was showing a form – pressed Back to reach the home screen")
                resetStability()
            }

            ScreenState.LOGIN_SCREEN -> if (sameStateCount >= 2) return authRequired()
            ScreenState.AMBIGUOUS -> if (sameStateCount >= 3) {
                return fail("More than one Sign In / Sign Out control is visible, so nothing was clicked. Screen: ${a.summary()}")
            }
            ScreenState.ERROR -> if (sameStateCount >= 5) return fail("The attendance app is showing an error: ${a.summary()}")
            else -> Unit
        }
    }

    private fun onOpenForm(a: ScreenAnalysis?, op: Operation) {
        when (a?.state) {
            ScreenState.DETAILS -> if (sameStateCount >= 2) {
                if (a.form != op) return fail("The attendance app opened the ${a.form?.label} form instead of ${op.label}. Nothing was submitted.")
                repo.info("${op.label} Details screen is open")
                selectLocation(a, op)
            }

            ScreenState.LOCATION_DIALOG -> if (sameStateCount >= 2) {
                if (a.form != op) return fail("The attendance app opened the ${a.form?.label} location list instead of ${op.label}. Nothing was submitted.")
                enter(Phase.PICK_LOCATION, DIALOG_TIMEOUT_MS, "Selecting the location…", firstPollMs = 0)
            }

            ScreenState.HOME_SIGNED_IN, ScreenState.HOME_SIGNED_OUT -> if (sameStateCount >= 3) {
                val status = homeStatus(a)
                if (status == op.resultStatus) {
                    // The attendance app recorded the action straight from the home button, without a form.
                    observedStatus = status
                    executedAt = System.currentTimeMillis()
                    note = "The attendance app completed the ${op.label} without showing a location screen, so the location could not be selected."
                    return succeed(op)
                }
            }

            ScreenState.CONFIRM_DIALOG -> handleConfirm(a)
            ScreenState.LOGIN_SCREEN -> if (sameStateCount >= 2) return authRequired()
            else -> Unit
        }
    }

    /** Opens the location list unless the dropdown already shows the configured location. */
    private fun selectLocation(a: ScreenAnalysis, op: Operation) {
        val want = config?.location ?: return fail("No location configured.")
        if (a.locationSelected(want)) {
            repo.info("Location already shows \"$want\"")
            enter(Phase.FILL_FORM, FORM_TIMEOUT_MS, "Checking the form…", firstPollMs = 0)
            return
        }
        val field = a.locationField
            ?: return fail("The ${op.label} Location field was not found on the attendance app's form. Nothing was submitted.")
        if (locationTries >= MAX_LOCATION_TRIES) {
            return fail("The location shows \"${a.selectedLocation ?: "nothing"}\" instead of \"$want\". Nothing was submitted.")
        }
        locationTries++
        if (!service.click(field)) return fail("The location field could not be opened. Nothing was submitted.")
        repo.info("Opened the location list")
        enter(Phase.PICK_LOCATION, DIALOG_TIMEOUT_MS, "Selecting \"$want\"…")
    }

    private fun onPickLocation(a: ScreenAnalysis?, op: Operation) {
        val want = config?.location ?: return fail("No location configured.")
        when (a?.state) {
            ScreenState.LOCATION_DIALOG -> if (sameStateCount >= 2) {
                if (a.form != op) return fail("The attendance app showed the ${a.form?.label} location list. Nothing was submitted.")
                val option = a.optionFor(want)
                if (option == null) {
                    val found = a.locationOptions.map { it.text }.distinct().joinToString().ifEmpty { "none" }
                    service.back() // close the list without choosing anything
                    return fail("Location \"$want\" is not available in the attendance app (found: $found). Nothing was submitted.")
                }
                if (!service.click(option)) return fail("Could not select \"$want\". Nothing was submitted.")
                repo.info("Selected location \"$want\"")
                enter(Phase.FILL_FORM, FORM_TIMEOUT_MS, "Verifying the location…")
            }

            // The list did not open (or closed again) – try the dropdown again, a limited number of times.
            ScreenState.DETAILS -> if (sameStateCount >= 4 && a.form == op) selectLocation(a, op)
            ScreenState.LOGIN_SCREEN -> if (sameStateCount >= 2) return authRequired()
            else -> Unit
        }
    }

    private fun onFillForm(a: ScreenAnalysis?, op: Operation) {
        val want = config?.location ?: return fail("No location configured.")
        when (a?.state) {
            ScreenState.DETAILS -> if (sameStateCount >= 2) {
                if (a.form != op) return fail("The attendance app switched to the ${a.form?.label} form. Nothing was submitted.")
                if (!a.locationSelected(want)) return selectLocation(a, op)

                val remarks = config?.remarks?.trim().orEmpty()
                if (remarks.isNotEmpty()) {
                    val field = a.remarksField
                    when {
                        field == null -> if (note == null) {
                            note = "The attendance app showed no Remarks field, so the remarks were not entered."
                            repo.warn(note!!)
                        }
                        a.remarksText?.trim() == remarks -> Unit
                        remarksTries < MAX_REMARKS_TRIES -> {
                            remarksTries++
                            if (!service.setText(field, remarks)) return fail("The remarks could not be entered. Nothing was submitted.")
                            repo.info("Entered remarks")
                            resetStability()
                            return
                        }
                        else -> return fail("The Remarks field did not keep the text. Nothing was submitted.")
                    }
                }

                val button = a.actionButton
                if (button == null || !ScreenAnalyzer.isButtonFor(button, op, labels)) {
                    return fail("The ${op.label} button on the form could not be identified safely. Nothing was submitted.")
                }
                if (!button.enabled) return // wait; times out with a message if it stays disabled
                if (request.mode == RunMode.DRY_RUN) {
                    return finish(
                        LogLevel.INFO,
                        "✅ Dry run passed: location \"$want\" is selected and verified" +
                            (if (remarks.isNotEmpty() && a.remarksField != null) ", remarks entered" else "") +
                            ", and the \"${labels.of(op)}\" button was identified. Nothing was submitted – " +
                            "the attendance app's form is left open; press Back in the attendance app to discard it." + (note?.let { " ⚠️ $it" } ?: ""),
                        AttemptResult.SUCCESS,
                    )
                }
                locationVerified = true
                if (!service.click(button)) return fail("The ${op.label} button could not be clicked.")
                executedAt = System.currentTimeMillis()
                repo.info("Clicked \"${labels.of(op)}\" with location \"$want\" – verifying…")
                enter(Phase.VERIFY, VERIFY_TIMEOUT_MS, "Verifying ${op.label}…")
            }

            ScreenState.LOCATION_DIALOG -> if (sameStateCount >= 3) {
                return fail("The location list did not close after selecting \"$want\". Nothing was submitted.")
            }
            ScreenState.LOGIN_SCREEN -> if (sameStateCount >= 2) return authRequired()
            else -> Unit
        }
    }

    private fun onVerify(a: ScreenAnalysis?, op: Operation) {
        when (a?.state) {
            ScreenState.HOME_SIGNED_IN, ScreenState.HOME_SIGNED_OUT -> {
                val status = homeStatus(a)
                observedStatus = status
                if (status == op.resultStatus && sameStateCount >= 2) return succeed(op)
                if (status == op.requiredStatus && sameStateCount >= UNCHANGED_POLLS) {
                    repo.recordStatus(status)
                    return fail("${op.label} was pressed but the attendance app still shows ${status.label}. Please check the attendance app.")
                }
            }
            ScreenState.CONFIRM_DIALOG -> handleConfirm(a)
            ScreenState.LOGIN_SCREEN -> if (sameStateCount >= 2) {
                return fail("The attendance app showed its login screen after ${op.label} was pressed. Please check the attendance app manually.")
            }
            else -> Unit
        }
    }

    private fun handleConfirm(a: ScreenAnalysis) {
        if (confirmClicked || sameStateCount < 2) return
        if (!repo.settings.value.allowConfirmDialog) {
            return fail("The attendance app asked for a confirmation. Nothing more was clicked. If this dialog is expected, enable \"Confirm dialog\" in Advanced settings.")
        }
        val button = a.confirmTargets.singleOrNull()
            ?: return fail("The confirmation dialog has no single Yes/Confirm button – stopped. Screen: ${a.summary()}")
        confirmClicked = true
        if (!service.click(button)) return fail("Could not click the confirmation button.")
        repo.info("Confirmed the attendance app's dialog")
        resetStability()
    }

    // ---- Outcomes ----------------------------------------------------------------------------

    private fun succeed(op: Operation) {
        val now = System.currentTimeMillis()
        repo.recordAction(op, config?.location, now)
        observedStatus = op.resultStatus
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a"))
        val where = if (locationVerified) " – location \"${config?.location}\"" else ""
        val msg = "✅ ${op.successPhrase} at $time$where (verified on the attendance app's screen)." +
            (note?.let { " ⚠️ $it" } ?: "")
        finish(LogLevel.INFO, msg, AttemptResult.SUCCESS)
        Notifier.success(service, op, "Attendance: ${op.successPhrase}", msg)
        Announcer.announceStatus(service, op.resultStatus)
    }

    private fun skip(message: String) {
        val m = "ℹ️ $message"
        finish(LogLevel.INFO, m, AttemptResult.SKIPPED)
        if (request.mode == RunMode.SCHEDULED) Notifier.info(service, request.op, "Attendance: no action needed", m)
    }

    private fun authRequired() {
        val m = "The attendance app is showing its login screen. Open the attendance app and log in yourself – this app never enters or stores credentials."
        finish(LogLevel.ERROR, "❌ $m", AttemptResult.FAILED)
        Notifier.authRequired(service, request.op, repo.settings.value.targetPackage)
        if (request.mode != RunMode.TEST_DETECT) Announcer.failure(service, request.op)
    }

    private fun fail(message: String) {
        finish(LogLevel.ERROR, "❌ $message", AttemptResult.FAILED)
        Notifier.failure(service, request.op, "$title failed", message)
        if (request.mode != RunMode.TEST_DETECT) Announcer.failure(service, request.op)
    }

    private fun finish(level: LogLevel, message: String, result: AttemptResult) {
        val wasTest = request.mode != RunMode.SCHEDULED
        phase = Phase.IDLE
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(unlockRunnable)
        handler.removeCallbacks(networkRunnable)
        Notifier.cancelUnlock(service)
        cpuLock?.takeIf { it.isHeld }?.release()
        screenLock?.takeIf { it.isHeld }?.release()
        cpuLock = null
        screenLock = null
        repo.runProgress.value = null
        repo.log(level, "$title: $message")
        attempt?.let {
            repo.saveAttempt(
                it.copy(
                    executedAt = executedAt, finishedAt = System.currentTimeMillis(),
                    previousStatus = previousStatus, finalStatus = observedStatus,
                    result = result, message = message,
                ),
            )
        }
        attempt = null
        if (wasTest) {
            repo.testResult.value = message
            // Bring this app back so the test result is visible.
            handler.postDelayed({
                service.startActivity(
                    Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }, 2500)
        }
        if (queue.isNotEmpty()) handler.postDelayed({ queue.removeFirstOrNull()?.let(::start) }, QUEUE_GAP_MS)
    }

    // ---- Helpers -----------------------------------------------------------------------------

    private fun enter(next: Phase, timeoutMs: Long, text: String, firstPollMs: Long = 1500) {
        phase = next
        deadline = SystemClock.elapsedRealtime() + timeoutMs
        resetStability()
        progress(text)
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, firstPollMs)
        pollQueued = true
    }

    private fun timeoutMessage(a: ScreenAnalysis?): String {
        val op = request.op?.label ?: "attendance"
        val what = when (phase) {
            Phase.FIND_HOME -> "The attendance app's home screen was not recognised within ${FIND_TIMEOUT_MS / 1000} s."
            Phase.OPEN_FORM -> "The $op Details screen did not open within ${FORM_TIMEOUT_MS / 1000} s. Nothing was submitted."
            Phase.PICK_LOCATION -> "The location list did not appear within ${DIALOG_TIMEOUT_MS / 1000} s. Nothing was submitted."
            Phase.FILL_FORM -> "The form could not be completed (location not confirmed or $op button disabled). Nothing was submitted."
            Phase.VERIFY -> "$op was pressed but the attendance app did not show the new status within ${VERIFY_TIMEOUT_MS / 1000} s. Please check the attendance app."
            else -> "Timed out."
        }
        val where = if (a == null) "The attendance app is not in front (current app: ${service.foregroundPackage() ?: "unknown"})"
        else "state ${a.state}; screen: ${a.summary()}"
        return "$what – $where"
    }

    private fun homeStatus(a: ScreenAnalysis) =
        if (a.state == ScreenState.HOME_SIGNED_IN) AttendanceStatus.SIGNED_IN else AttendanceStatus.SIGNED_OUT

    private fun actionLower() = request.op?.label?.lowercase() ?: "check attendance"

    private fun progress(text: String) {
        repo.runProgress.value = "$title: $text"
    }

    private fun resetStability() {
        lastKey = null
        sameStateCount = 0
    }

    private fun isUnlocked() = power.isInteractive && !keyguard.isKeyguardLocked

    private fun hasNetwork(): Boolean {
        val cm = service.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private companion object {
        const val POLL_MS = 1000L
        const val FIND_TIMEOUT_MS = 45_000L
        const val FORM_TIMEOUT_MS = 30_000L
        const val DIALOG_TIMEOUT_MS = 20_000L
        const val VERIFY_TIMEOUT_MS = 45_000L
        const val UNCHANGED_POLLS = 10
        const val MAX_BACK_PRESSES = 3
        const val MAX_LOCATION_TRIES = 2
        const val MAX_REMARKS_TRIES = 2
        const val NETWORK_RETRIES = 3
        const val NETWORK_RETRY_MS = 30_000L
        const val QUEUE_GAP_MS = 3000L
        const val SCREEN_MAX_MS = 4 * 60_000L
        const val RUN_MAX_MS = 70 * 60_000L
    }
}
