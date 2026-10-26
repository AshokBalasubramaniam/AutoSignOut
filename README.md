# Auto Attendance

An Android app (Kotlin, Jetpack Compose, Material 3, Room) that opens the **attendance app** at times you choose and performs **Sign In** and/or **Sign Out** with the **location you configured** for each. It never enters or stores your the attendance app username, password, or OTP, never bypasses the lock screen, and stops instead of guessing whenever the attendance app's screen is not exactly what it expects.

## Build and install

1. Open the project folder in Android Studio (Ladybug or newer, JDK 17).
2. Connect your phone with USB debugging on, then press **Run**. You can also build from the command line:
   ```bash
   ./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
   ./gradlew testDebugUnitTest    # schedule + screen-recognition unit tests
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
- Requirements: minSdk 26 (Android 8.0), target/compile SDK 35.
- **Installing by hand (file manager, WhatsApp, Drive…):** use `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. It is signed with the key in `keystore/release.jks` (passwords in `keystore.properties`). Both files are kept out of git: **back them up**, because every future update must be signed with the same key, or Android will refuse it as "App not installed".
  - Never install an `*-unsigned.apk`; Android rejects unsigned APKs.
  - If a build signed with another key is already installed (for example a debug build from Android Studio), uninstall it first.
  - **"App blocked to protect your device"** is Play Protect's fraud protection. It blocks sideloaded apps that use an Accessibility Service when they are installed from a file manager, browser or chat app, and it cannot be avoided in code. Install over USB instead: turn on USB debugging (and on Samsung, turn **Auto Blocker** off), connect the phone, and run `./install-to-phone.sh`. Alternatively, pause Play Protect while installing (Play Store › profile › Play Protect › ⚙ › "Scan apps with Play Protect" off), then turn it back on.
- The package name is `com.autoattendance.app`. It differs from earlier builds, so it installs as a separate app: uninstall the old one, then redo Setup (accessibility, notifications, battery) and your schedules.

## One-time setup (the **Setup** card)

| Step | Why it is needed |
|---|---|
| **Select the attendance app.** Apps with "attendance" in the name are listed first; use the search box to find yours. | The app needs to know which app to open and read. No package name is hardcoded. |
| **Turn on the accessibility service:** Settings › Accessibility › *Auto Attendance*. | It is the only supported way to read another app's screen and press its controls. Events are filtered to the attendance app's package only. |
| On Android 13+ the switch may be greyed out ("Restricted setting"). Fix: **App info › ⋮ › Allow restricted settings**, then enable it. | Android blocks accessibility for sideloaded apps until you allow it. |
| **Allow notifications.** | Success, failure, "unlock required", and "log in to the attendance app" notifications. |
| **Allow exact alarms** (Android 12/12L only; granted automatically on 13+). | Fires at exactly the time you set, even in Doze. |
| **Disable battery optimisation.** | Stops Android from delaying the alarm or killing the service. |
| **OEM settings.** Xiaomi/POCO: *Autostart* ON, Battery saver "No restrictions". Realme/OPPO/OnePlus: *Auto launch* ON, "Allow background activity". Vivo: *High background power usage* ON. Samsung: add to "Never sleeping apps". | These vendors kill background apps aggressively. Without these settings the alarm or the service may not run. |

Then, for **each** of Sign In and Sign Out, set the time, the dates, and the **location** (see below), and turn on the **Automation** master switch.

## Screens

The bottom bar has four tabs.

- **Home** shows only the *Automation Overview*:
  - The master **Automation ON/OFF** switch.
  - The current status (Signed In / Signed Out / Unknown) and when it was last seen.
  - The next Sign In and Sign Out, each with its location and a badge: Active / Paused / Off / No location / No date. Tap one to open Schedule.
  - The last successful action.
  - **Needs attention**: anything that would stop a run (no attendance app selected, accessibility off, notifications off, no location). Tap an item to go where you can fix it.
- **Schedule**: one card for **Automatic Sign In** and one for **Automatic Sign Out**. They are fully independent. Each has:
  - Enable switch and time picker
  - **Location** selection dialog (Work from Home / Office / Client Location / On-Duty). Nothing is preselected, and a run with no location stops with an error.
  - Optional **Remarks**, max 200 characters. They are typed into the attendance app only if it shows a Remarks field.
  - **Recurring weekdays** (Mon–Fri) with an optional **date range**, or **specific dates**: tap **Choose dates** and select as many dates as you like in the calendar. Selected dates are highlighted, Saturday, Sunday and past days cannot be selected, and the chosen dates are listed below the button (tap one to remove it).
  - "Check a date", which explains whether a given date would run
  - The next 5 run times. The list updates every minute: when a run's time passes, it drops off and the next chosen date moves up.
- **Logs**: attempts and diagnostics (see below).
- **Settings**: setup checks, tests, audio, and advanced labels.

Saturday and Sunday are always excluded. The weekend chips are disabled, the calendar does not allow weekend dates, and the scheduler re-checks the date when the alarm fires. Any change to a time, date, weekday, location, or switch immediately recalculates the alarms.

## Motion

All animation lives in `ui/Motion.kt` and uses shared tokens: 150 ms for micro-interactions, 250 ms for selection and content changes, 350 ms for screen transitions. Movement uses Material 3 emphasized easing or no-bounce springs.

- **Tabs:** a "fade through" transition with a short slide in the direction of travel. Back slides the other way, and each tab keeps its scroll position.
- **Dialogs:** every dialog uses `AppDialog`. The background dims in and the content scales up from 92 %, and the same happens in reverse on Back, an outside tap, or a button. Actions run on the first tap and only once.
- **Controls:** buttons, chips, rows and calendar days shrink slightly while pressed, without delaying the click. Enabled/disabled and selected states fade, and segmented controls slide their highlight.
- **Appearing content:** notes, validation errors, banners and lists expand and fade instead of jumping.
- **Feedback:** a run in progress shows a spinner banner, and a verified Sign In/Out makes the last-action row glow green once.
- **Reduced motion:** when Android's animator duration scale is off ("Remove animations"), every animation snaps instead.

## How a scheduled run works

The attendance app's flow, taken from the reference screenshots: Home screen → **Sign Out** (or **Sign In**) button → **Sign-Out Details** form (Location dropdown, Remarks, **Sign Out** button) → **Select your Sign-Out Location** list.

1. **AlarmManager** (`setAlarmClock`: exact, works in Doze) fires. Sign In and Sign Out use separate alarms. The next alarm is queued immediately. If both fire together, the second waits for the first to finish.
2. The date is re-checked (weekend, weekday, range, selected dates). A skipped date is logged as **Skipped**. An alarm more than 30 min late (phone was off) is logged as **Failed** and you are notified. It does not sign in or out late.
3. **Network check.** With no connection it retries 3 times, 30 s apart, then stops.
4. **Locked phone.** The screen turns on and Android's *own* unlock prompt appears. A notification asks you to unlock. The app waits up to 30 min (configurable). **A PIN, pattern, password, or biometric lock is never bypassed.**
5. **The attendance app is opened** (not relaunched if it is already in front). If the attendance app is still showing a form from earlier, the app presses Back until the home screen is visible.
6. **Status check on the home screen.** At least one of *Hello / Shift / Time elapsed / Regularize* must be visible, plus exactly one attendance button.
   - Sign In run with **Sign Out** showing: already signed in → **Skipped**, nothing is clicked. The Sign Out run works the same way in reverse.
   - Login screen → stops, and a notification asks you to log in to the attendance app yourself.
   - Error, unrecognised, or ambiguous screen (both buttons, or two of one) → stops without clicking.
7. **Open the form.** It taps the home button for *this* operation only, and waits for the matching *Sign-In/Sign-Out Details* screen. If the other operation's form appears, it stops.
8. **Location.** It opens the dropdown and taps the list entry whose text **exactly** matches your configured location (case, spaces, and hyphens are ignored). If that entry is missing or appears twice, it closes the list, stops, and logs *"Location 'X' is not available in the attendance app (found: …)"*. It then checks that the dropdown really shows the configured location. It never picks the first entry.
9. **Remarks** (only if configured) are entered and read back.
10. **Submit.** Right before each tap, the target is re-checked: it must show this operation's label and not the opposite one. Accessibility `ACTION_CLICK` is used first; if that fails, it taps the centre of *that same control's* bounds. No fixed coordinates are used anywhere.
11. **Verification.** It succeeds only when the attendance app's home screen shows the new status for 2 consecutive checks within 45 s. The app then records the result, posts a notification, and says **"Signed in successfully"** / **"Signed out successfully"**. That is announced once per status per day.
12. Any failure gives a distinct alert tone, a spoken failure message, an error notification, and a **Failed** log entry with the reason.

## Automation Logs screen

- Each attempt is stored in **Room**, newest first. Each card shows: `09:27 AM – Sign In – Office – Success`, plus date, scheduled time, start time, execution time (when the final button was pressed), previous → final status, result (**Success / Failed / Skipped / Pending**), and the error or details.
- **Filters:** Sign In, Sign Out, Success, Failed, Skipped, Pending. Chips in the same group combine; operation and result groups intersect.
- **Diagnostics** tab: step-by-step events for troubleshooting.
- **Clear** deletes everything after a confirmation.
- No credentials, OTPs, tokens, or values you typed into the attendance app are logged. GPS coordinates in screen text are masked (`•••`), the table keeps the newest 500 attempts, and step logs reach logcat only in debug builds.

## Testing (the **Test automation** card)

| Button | What it does | Clicks? |
|---|---|---|
| **Open attendance app** | Opens the attendance app so you can check that you are logged in. | — |
| **Detect status (no click)** | Reads the home screen and reports the status and which button was identified. | Nothing |
| **Dry run Sign In / Sign Out** | Runs the whole flow up to, but **not including**, the final Sign In/Sign Out: it opens the Details form, selects and verifies the location, enters remarks, and identifies the submit button. The attendance app's form is left open; press Back in the attendance app to discard it. | Home button, dropdown, list entry |
| **Execute Sign In / Sign Out…** | A real run, only after you confirm the dialog. | Everything, including submit |

Suggested checks:

| Scenario | Expected result |
|---|---|
| Logged in and signed in → *Detect* | "Status: Signed In. The "Sign Out" button was identified." |
| Signed in → *Dry run Sign In* | Skipped: "Already signed in". |
| *Dry run Sign Out* with Location = Work from Home | "Dry run passed: location "Work from Home" is selected and verified…" |
| Location set to a name the attendance app does not list (Advanced › Location choices) → dry run | Failed: "Location … is not available in the attendance app (found: …)" |
| Logged out of the attendance app → *Detect* | Failed, plus a "Log in to the attendance app" notification |
| Time set 2 min ahead, phone locked | Screen wakes, unlock prompt and notification; continues after you unlock |
| Saturday/Sunday | Weekend chips disabled; *Check a date* → "Saturday and Sunday are always excluded" |

## If the attendance app looks different

Use **Advanced**:
- **Sign In / Sign Out button text**: change these if the attendance app renames the buttons. The Details screen and the location list are recognised from the same words ("Sign-Out Details", "Sign Out Location", "Select your Sign-Out Location").
- **Home screen texts**: words that prove you are on the attendance home screen.
- **Location choices**: the options offered in this app's location dialog. They must match the attendance app's list.
- **Confirm dialog**: off by default. If the attendance app asks "Are you sure?", the run stops and tells you. Turn this on to let it press that dialog's single Yes/Confirm/OK button. A dialog button labelled Sign In/Sign Out is never treated as "Yes".

> **Sign In assumption:** the screenshots show the Sign Out flow. Sign In is assumed to be the mirror image (*Sign-In Details*, *Sign In Location*, *Select your Sign-In Location*). Run **Dry run Sign In** once while signed out to confirm. If the attendance app signs you in straight from the home button with no form, the run is still verified and logged, with the note "location could not be selected".

## Permissions used

| Permission | Purpose |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` (service binding) | Read the attendance app's screen and press its controls. Required for any cross-app automation. |
| `POST_NOTIFICATIONS` | Results, errors, unlock and login requests. |
| `USE_EXACT_ALARM` (13+) / `SCHEDULE_EXACT_ALARM` (12/12L) | Exact Sign In/Sign Out times. |
| `RECEIVE_BOOT_COMPLETED` | Restore both alarms after a restart, time change, or app update. |
| `WAKE_LOCK` | Turn the screen on and keep the CPU awake during a run. |
| `ACCESS_NETWORK_STATE` | Check for a connection before opening the attendance app. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Shows the system "don't optimise" dialog from Setup. |

- Package visibility uses a `<queries>` launcher intent, not `QUERY_ALL_PACKAGES`.
- No internet, location, contacts, storage, or overlay permission is used. (The attendance app reads your GPS location itself; this app does not.)
- **No foreground service.** The system keeps a bound accessibility service alive, and Android exempts it from background-activity-launch restrictions, so it can open the attendance app from the background. An extra foreground service would add a permanent notification without making anything more reliable.

## Android limitations

- **A locked phone can't be operated without you.** With a secure lock, Android does not let any app interact with another app behind the lock screen. The app wakes the screen, shows the unlock prompt, and waits. Locked-screen automation is therefore **not** guaranteed on any device. It needs you to unlock.
- **The accessibility service must stay enabled.** Some OEMs switch it off after updates or a force-stop. If it is off at the scheduled time, you get a failure notification and a Failed log entry.
- **Doze / battery savers.** `setAlarmClock` is exempt from Doze, but aggressive OEM task killers can still stop the app. Follow the OEM settings above. `setAlarmClock` also shows an alarm icon in the status bar while a run is scheduled.
- **Reboot.** Schedules are restored on boot. A run whose time passed while the phone was off is missed, not run late.
- **Attendance app updates.** If the attendance app changes its texts or layout, detection fails safely (nothing is submitted) until you update the labels in Advanced.
- **GPS in the attendance app.** The attendance app shows a location accuracy box. If the attendance app refuses to submit without a GPS fix, the run times out in verification, and the log contains the screen text.
- Voice uses the **alarm** audio stream, so it is audible even on silent. Turn it off in *Audio notifications*.
- For Google Play distribution, `USE_EXACT_ALARM` and the accessibility usage would need extra justification. They are fine for a personal APK.

## Project layout

```
app/src/main/java/com/autoattendance/app/
  automation/  AttendanceAccessibilityService (reads/clicks the attendance app), AutomationRunner (state machine),
               ScreenAnalyzer (pure, unit-tested), WakeActivity (unlock prompt)
  schedule/    ScheduleCalculator (pure, unit-tested), AlarmScheduler (one alarm per operation),
               AlarmReceiver, BootReceiver
  feedback/    Announcer (TTS + tones), Notifier (channels: results, errors, action required)
  data/        Models, Repository (settings in SharedPreferences), AttemptDatabase (Room logs)
  ui/          MainActivity, MainScreen (dashboard), OperationSection (per-operation settings),
               LogsScreen, Components, SystemChecks
app/src/main/res/xml/accessibility_service_config.xml
```
