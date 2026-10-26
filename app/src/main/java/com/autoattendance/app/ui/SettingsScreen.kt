@file:OptIn(ExperimentalLayoutApi::class)

package com.autoattendance.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.autoattendance.app.automation.AttendanceAccessibilityService
import com.autoattendance.app.automation.RunMode
import com.autoattendance.app.automation.RunRequest
import com.autoattendance.app.automation.ScreenAnalyzer
import com.autoattendance.app.data.AttendanceStatus
import com.autoattendance.app.data.AutomationSettings
import com.autoattendance.app.data.Operation
import com.autoattendance.app.data.Repository
import com.autoattendance.app.feedback.Announcer
import com.autoattendance.app.schedule.AlarmScheduler
import com.autoattendance.app.ui.theme.LocalPalette

@Composable
fun SettingsScreen(resumeTick: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    val repo = remember { Repository.get(ctx) }
    val settings by repo.settings.collectAsState()
    val progress by repo.runProgress.collectAsState()
    val testResult by repo.testResult.collectAsState()
    val serviceConnected by AttendanceAccessibilityService.connected.collectAsState()

    fun update(transform: (AutomationSettings) -> AutomationSettings) {
        repo.updateSettings(transform)
        AlarmScheduler.reschedule(ctx)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader("Settings", "Setup, tests, sounds and advanced options")
        SetupCard(resumeTick, settings, serviceConnected, ::update)
        TestCard(progress, testResult, serviceConnected, settings)
        AudioCard(settings, repo)
        AdvancedCard(settings, repo)
    }
}

// ---- Setup ----------------------------------------------------------------------------------

@Composable
private fun SetupCard(
    resumeTick: Int,
    settings: AutomationSettings,
    serviceConnected: Boolean,
    update: ((AutomationSettings) -> AutomationSettings) -> Unit,
) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val accessibility = remember(resumeTick, serviceConnected) { SystemChecks.accessibilityEnabled(ctx) }
    val notifications = remember(resumeTick) { SystemChecks.notificationsEnabled(ctx) }
    val exact = remember(resumeTick) { SystemChecks.exactAlarmsAllowed(ctx) }
    val battery = remember(resumeTick) { SystemChecks.batteryUnrestricted(ctx) }
    val installed = remember(resumeTick, settings.targetPackage) { SystemChecks.isInstalled(ctx, settings.targetPackage) }
    var showPicker by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    AppCard {
        CardHeader(Icons.Outlined.Checklist, p.primary, p.primarySoft, "Setup", "Required once for automation to work")
        CheckRow(
            "Attendance app: " + (settings.targetAppName?.let { "$it (${settings.targetPackage})" } ?: "not selected"),
            installed, if (installed) "Change" else "Select",
        ) { showPicker = true }
        CheckRow("Accessibility service", accessibility && serviceConnected, "Open") {
            SystemChecks.openAccessibilitySettings(ctx)
        }
        if (!accessibility) {
            Hint(
                "If the switch is greyed out (\"Restricted setting\"), open App info › ⋮ › " +
                    "Allow restricted settings, then try again.",
            )
            AppButton("Open App info", { SystemChecks.openAppDetails(ctx) }, kind = ButtonKind.Text)
        }
        CheckRow("Notifications", notifications, "Allow") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                SystemChecks.openNotificationSettings(ctx)
            }
        }
        CheckRow("Exact alarms", exact, "Allow") { SystemChecks.openExactAlarmSettings(ctx) }
        if (!exact) Hint("Without exact alarms Android may run the automation several minutes late.")
        CheckRow("Battery optimisation disabled", battery, "Fix") { SystemChecks.requestBatteryExemption(ctx) }
        Hint("On Xiaomi/Redmi/POCO, Realme/OPPO/OnePlus, Vivo and Samsung, also enable Autostart and set Battery to \"No restrictions\" for this app (see README).")
        Hint("A locked phone: the app wakes the screen and shows Android's own unlock prompt. It never bypasses your PIN, pattern or fingerprint – you must unlock for the automation to continue.")
    }

    if (showPicker) {
        AppPickerDialog(
            onDismissed = { showPicker = false },
            onPick = { app -> update { it.copy(targetPackage = app.packageName, targetAppName = app.label) } },
        )
    }
}

@Composable
private fun AppPickerDialog(onDismissed: () -> Unit, onPick: (LaunchableApp) -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { SystemChecks.launchableApps(ctx) }
    var query by remember { mutableStateOf("") }
    val shown = apps.filter {
        query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
    }
    val reduce = LocalReduceMotion.current
    AppAlertDialog(
        onDismissed = onDismissed,
        title = "Select the attendance app",
        buttons = { c -> AppButton("Cancel", { c.close() }, kind = ButtonKind.Text) },
    ) { c ->
        OutlinedTextField(
            query, { query = it }, label = { Text("Search") }, singleLine = true, shape = FieldShape,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(shown, key = { it.packageName }) { app ->
                ListItem(
                    headlineContent = {
                        Text(
                            app.label,
                            fontWeight = if (SystemChecks.looksLikeAttendanceApp(app)) FontWeight.Bold else null,
                        )
                    },
                    supportingContent = { Text(app.packageName) },
                    // Results re-order smoothly while typing in the search box.
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = Motion.tween(reduce, Motion.SHORT),
                            placementSpec = Motion.spring(reduce),
                            fadeOutSpec = Motion.tween(reduce, Motion.SHORT),
                        )
                        .clickable { c.close { onPick(app) } },
                )
            }
        }
    }
}

// ---- Test -----------------------------------------------------------------------------------

@Composable
private fun TestCard(
    progress: String?,
    testResult: String?,
    serviceConnected: Boolean,
    settings: AutomationSettings,
) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val repo = remember { Repository.get(ctx) }
    var confirmOp by remember { mutableStateOf<Operation?>(null) }
    val idle = progress == null && serviceConnected

    fun run(op: Operation?, mode: RunMode) {
        val service = AttendanceAccessibilityService.instance
        if (service == null) {
            repo.testResult.value = "❌ Turn on the accessibility service first (Setup)."
            return
        }
        service.runner.start(RunRequest(op, mode))
    }

    AppCard {
        CardHeader(Icons.Outlined.Science, p.primary, p.primarySoft, "Test automation", "Check detection before relying on it")
        Hint(
            "1) Open the attendance app and make sure you are logged in.  2) \"Detect status\" reads the attendance app's home screen " +
                "and clicks nothing.  3) \"Dry run\" opens the Sign In/Sign Out Details form, selects your " +
                "configured location and checks the button, but never presses the final Sign In/Sign Out.  " +
                "4) Only if you want, \"Execute…\" performs a real one after you confirm.",
        )
        val gap = Arrangement.spacedBy(8.dp)
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            AppButton(
                "Open attendance app",
                {
                    settings.targetPackage?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
                        ?.let(ctx::startActivity)
                },
                kind = ButtonKind.Outlined,
                enabled = SystemChecks.isInstalled(ctx, settings.targetPackage),
            )
            AppButton("Detect status (no click)", { run(null, RunMode.TEST_DETECT) }, enabled = idle)
        }
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            Operation.entries.forEach { op ->
                AppButton("Dry run ${op.label}", { run(op, RunMode.DRY_RUN) }, kind = ButtonKind.Tonal, enabled = idle)
            }
        }
        FlowRow(horizontalArrangement = gap, verticalArrangement = gap) {
            Operation.entries.forEach { op ->
                AppButton("Execute ${op.label}…", { confirmOp = op }, kind = ButtonKind.Outlined, enabled = idle)
            }
        }
        ExpandFade(progress != null) {
            AppButton(
                "Cancel run",
                { AttendanceAccessibilityService.instance?.runner?.cancel("Cancelled by user") },
                kind = ButtonKind.Text,
            )
        }
        ExpandFade(!serviceConnected) { Hint("Tests need the accessibility service (see Setup).") }
        ProgressBanner(progress)
        ResultBanner(testResult)
    }

    confirmOp?.let { op ->
        val location = settings.schedule(op).location
        AppAlertDialog(
            onDismissed = { confirmOp = null },
            title = "${op.label} now?",
            buttons = { c ->
                AppButton("Cancel", { c.close() }, kind = ButtonKind.Text)
                // close() runs the action once, so a double tap can never start two real runs.
                AppButton("${op.label} now", { c.close { run(op, RunMode.TEST_EXECUTE) } }, enabled = location != null)
            },
        ) {
            Text(
                "This opens the attendance app and really performs \"${settings.buttonLabel(op)}\" with location " +
                    "\"${location ?: "not selected"}\". Your attendance will be recorded.",
                color = p.text,
            )
        }
    }
}

// ---- Audio ----------------------------------------------------------------------------------

@Composable
private fun AudioCard(settings: AutomationSettings, repo: Repository) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    AppCard {
        CardHeader(
            Icons.AutoMirrored.Outlined.VolumeUp, p.primary, p.primarySoft,
            "Audio notifications", "Spoken result after the attendance app confirms it",
        ) {
            PillSwitch(settings.voiceEnabled, { on -> repo.updateSettings { it.copy(voiceEnabled = on) } })
        }
        Hint("Says \"Signed in/out successfully\" only after the attendance app shows the new status, once per day. Failures use a different alert.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("▶ Signed in", { Announcer.preview(ctx, AttendanceStatus.SIGNED_IN) }, kind = ButtonKind.Text)
            AppButton("▶ Signed out", { Announcer.preview(ctx, AttendanceStatus.SIGNED_OUT) }, kind = ButtonKind.Text)
            AppButton("▶ Failure alert", { Announcer.failure(ctx, null, "This is the failure alert") }, kind = ButtonKind.Text)
        }
    }
}

// ---- Advanced -------------------------------------------------------------------------------

@Composable
private fun AdvancedCard(settings: AutomationSettings, repo: Repository) {
    val p = LocalPalette.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    AppCard {
        CardHeader(Icons.Outlined.Tune, p.primary, p.primarySoft, "Advanced", "Only if the attendance app renames its controls") {
            val turn by animateFloatAsState(if (expanded) 180f else 0f, motionTween(Motion.MEDIUM), label = "chevron")
            CircleIconButton(
                Icons.Rounded.KeyboardArrowDown, if (expanded) "Hide" else "Show", { expanded = !expanded },
                modifier = Modifier.graphicsLayer { rotationZ = turn },
            )
        }
        ExpandFade(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hint("Matching ignores case, spaces and hyphens.")
        val inBlank = settings.signInLabel.isBlank()
        val outBlank = settings.signOutLabel.isBlank()
        val sameLabels = !inBlank && ScreenAnalyzer.normalize(settings.signInLabel) == ScreenAnalyzer.normalize(settings.signOutLabel)
        OutlinedTextField(
            settings.signInLabel, { v -> repo.updateSettings { it.copy(signInLabel = v) } },
            label = { Text("Sign In button text") }, singleLine = true, shape = FieldShape, modifier = Modifier.fillMaxWidth(),
            isError = inBlank || sameLabels,
        )
        FieldError(inBlank, "Required – without it Sign In can never be found.")
        OutlinedTextField(
            settings.signOutLabel, { v -> repo.updateSettings { it.copy(signOutLabel = v) } },
            label = { Text("Sign Out button text") }, singleLine = true, shape = FieldShape, modifier = Modifier.fillMaxWidth(),
            isError = outBlank || sameLabels,
        )
        FieldError(outBlank, "Required – without it Sign Out can never be found.")
        FieldError(sameLabels, "Sign In and Sign Out must be different, otherwise every run stops as ambiguous.")
        OutlinedTextField(
            settings.homeMarkers, { v -> repo.updateSettings { it.copy(homeMarkers = v) } },
            label = { Text("Home screen texts (comma separated)") }, shape = FieldShape,
            modifier = Modifier.fillMaxWidth(),
            isError = settings.homeMarkerList.isEmpty(),
        )
        FieldError(settings.homeMarkerList.isEmpty(), "Add at least one text, otherwise the home screen can never be recognised.")
        OutlinedTextField(
            settings.locationOptions, { v -> repo.updateSettings { it.copy(locationOptions = v) } },
            label = { Text("Location choices (comma separated, as in the attendance app)") }, shape = FieldShape,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(
            "Confirm dialog",
            "If the attendance app asks \"Are you sure?\", click its single Yes/Confirm/OK button.",
            settings.allowConfirmDialog,
        ) { on -> repo.updateSettings { it.copy(allowConfirmDialog = on) } }
        Text("Wait for unlock: ${settings.unlockWaitMinutes} min", style = MaterialTheme.typography.bodyLarge, color = p.text)
        Slider(
            value = settings.unlockWaitMinutes.toFloat(),
            onValueChange = { v -> repo.updateSettings { it.copy(unlockWaitMinutes = v.toInt()) } },
            valueRange = 5f..60f,
            steps = 10,
        )
        }
        }
    }
}

/** Validation message that slides in under a field instead of pushing the form abruptly. */
@Composable
private fun FieldError(visible: Boolean, text: String) {
    ExpandFade(visible) { FieldNote(text, error = true) }
}

/** Result of the last test: green for ✅, red for ❌, neutral otherwise. New results cross-fade in. */
@Composable
private fun ResultBanner(result: String?) {
    val p = LocalPalette.current
    val last = remember { arrayOf("") }
    if (result != null) last[0] = result
    ExpandFade(result != null) {
        FadeSwap(result ?: last[0], Modifier.fillMaxWidth()) { text ->
            val (fg, bg) = when {
                text.startsWith("✅") -> p.green to p.greenSoft
                text.startsWith("❌") -> p.danger to p.dangerSoft
                else -> p.text to p.primarySofter
            }
            Surface(shape = FieldShape, color = bg, modifier = Modifier.fillMaxWidth()) {
                Text(text, Modifier.padding(12.dp), color = fg)
            }
        }
    }
}
