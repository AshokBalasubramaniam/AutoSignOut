package com.autoattendance.app.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.autoattendance.app.data.AttendanceStatus
import com.autoattendance.app.data.Repository
import com.autoattendance.app.feedback.Announcer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Reads the attendance app's UI and operates its Sign In / Sign Out forms for [AutomationRunner]. Also
 * passively watches the attendance app so that a real Sign In / Sign Out (button label actually changing) can be announced.
 */
class AttendanceAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AttendanceAccessibilityService? = null
            private set
        val connected = MutableStateFlow(false)

        private const val STABLE_MS = 2500L
        private const val SESSION_WINDOW_MS = 60 * 60 * 1000L
    }

    lateinit var runner: AutomationRunner
        private set

    private val repo by lazy { Repository.get(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var candidate: AttendanceStatus? = null
    private var candidateSince = 0L
    private val trackCheck = Runnable { trackStatus() }

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) runner.onUserPresent()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runner = AutomationRunner(this)
        instance = this
        connected.value = true
        ContextCompat.registerReceiver(
            this, userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            repo.settings.map { it.targetPackage }.distinctUntilChanged().collect(::applyPackageFilter)
        }
        repo.info("Accessibility service connected")
    }

    /** Only receive events from the attendance app – nothing else on the phone is observed. */
    private fun applyPackageFilter(pkg: String?) {
        val info = serviceInfo ?: return
        info.packageNames = arrayOf(pkg ?: packageName)
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = repo.settings.value.targetPackage ?: return
        if (event.packageName?.toString() != pkg) return
        handler.removeCallbacks(trackCheck)
        handler.postDelayed(trackCheck, 800)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance !== this) return
        instance = null
        connected.value = false
        runner.cancel("Accessibility service was turned off")
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(userPresentReceiver) }
        scope.cancel()
        repo.warn("Accessibility service disconnected")
    }

    // ---- Screen reading -------------------------------------------------------------------

    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    fun currentLabels(): Labels = repo.settings.value.let { Labels(it.signInLabel, it.signOutLabel, it.homeMarkerList) }

    /** Analyses the attendance app if it is the app in front, otherwise returns null. */
    fun analyzeTarget(labels: Labels = currentLabels()): ScreenAnalysis? {
        val pkg = repo.settings.value.targetPackage ?: return null
        val active = rootInActiveWindow ?: return null
        if (active.packageName?.toString() != pkg) return null
        val roots = runCatching { windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .filter { it.packageName?.toString() == pkg }
            .ifEmpty { listOf(active) }
        val budget = intArrayOf(MAX_NODES)
        return ScreenAnalyzer.analyze(roots.map { snapshot(it, 0, budget) }, labels)
    }

    private fun snapshot(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): UiNode {
        val r = Rect().also(node::getBoundsInScreen)
        val kids = if (depth >= MAX_DEPTH) emptyList() else (0 until node.childCount).mapNotNull { i ->
            if (budget[0]-- <= 0) return@mapNotNull null
            node.getChild(i)?.takeIf { it.isVisibleToUser }?.let { snapshot(it, depth + 1, budget) }
        }
        return UiNode(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            className = node.className?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            password = node.isPassword,
            editable = node.isEditable,
            bounds = Box(r.left, r.top, r.right, r.bottom),
            handle = node,
            children = kids,
        )
    }

    /**
     * Clicks the node identified by text/label. Falls back to tapping the centre of that node's
     * current bounds, but only when the attendance app's own window is the one at that spot.
     */
    fun click(target: UiNode): Boolean {
        val node = target.handle as? AccessibilityNodeInfo ?: return false
        if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || !inTargetApp(node)) return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        // Bounds re-read after refresh(): the snapshot's bounds may be stale if the layout moved.
        val b = Rect().also(node::getBoundsInScreen)
        if (b.isEmpty) return false
        // A tap lands on whatever window is on top – never tap through another app's overlay.
        if (topWindowPackageAt(b.centerX(), b.centerY()) != repo.settings.value.targetPackage) return false
        val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build(),
            null, null,
        )
    }

    /** Replaces the text of an input field (used only for the optional Remarks). */
    fun setText(target: UiNode, text: String): Boolean {
        val node = target.handle as? AccessibilityNodeInfo ?: return false
        // Never type into a password field, or into anything outside the attendance app.
        if (!node.refresh() || !node.isVisibleToUser || !node.isEditable || node.isPassword || !inTargetApp(node)) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** System Back – only used to leave an attendance app form or list, and only while that app is in front. */
    fun back(): Boolean {
        val pkg = repo.settings.value.targetPackage ?: return false
        return foregroundPackage() == pkg && performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** [node] belongs to the attendance app and that app is still the one in front. */
    private fun inTargetApp(node: AccessibilityNodeInfo): Boolean {
        val pkg = repo.settings.value.targetPackage ?: return false
        return node.packageName?.toString() == pkg && foregroundPackage() == pkg
    }

    /** Package of the top-most window covering the screen point, or null if unknown. */
    private fun topWindowPackageAt(x: Int, y: Int): String? = runCatching { windows }.getOrDefault(emptyList())
        .sortedByDescending { it.layer }
        .firstOrNull { w -> Rect().also(w::getBoundsInScreen).contains(x, y) }
        ?.root?.packageName?.toString()

    // ---- Passive status tracking (for Sign In / manual Sign Out announcements) -------------

    private fun trackStatus() {
        val state = when (analyzeTarget()?.state) {
            ScreenState.HOME_SIGNED_IN -> AttendanceStatus.SIGNED_IN
            ScreenState.HOME_SIGNED_OUT -> AttendanceStatus.SIGNED_OUT
            else -> return
        }
        val now = SystemClock.elapsedRealtime()
        if (state != candidate) {
            candidate = state
            candidateSince = now
            handler.postDelayed(trackCheck, STABLE_MS + 100)
            return
        }
        val heldFor = now - candidateSince
        if (heldFor < STABLE_MS) {
            handler.postDelayed(trackCheck, STABLE_MS - heldFor + 100)
            return
        }
        confirmStatus(state)
    }

    /** Called once a status has been shown unchanged for [STABLE_MS] (filters loading flicker). */
    private fun confirmStatus(state: AttendanceStatus) {
        val prev = repo.status.value
        val wall = System.currentTimeMillis()
        if (prev.status == state && wall - prev.observedAt < 30_000) return
        repo.recordStatus(state, wall)
        if (runner.isRunning) return // the runner verifies and announces its own result

        val prevDay = Instant.ofEpochMilli(prev.observedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val sawChange = prev.status != state && prev.status != AttendanceStatus.UNKNOWN &&
            wall - prev.observedAt < SESSION_WINDOW_MS && prevDay == LocalDate.now()
        if (sawChange) {
            repo.info("Attendance status changed: ${prev.status.label} → ${state.label}")
            Announcer.announceStatus(this, state)
        }
    }
}

private const val MAX_DEPTH = 60
private const val MAX_NODES = 4000
