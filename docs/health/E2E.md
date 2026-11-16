# Health Beta: end-to-end tests on simulators and emulators

The behavior suite (`tests/behavior`) proves the cores; it never renders a view or touches the OS. The E2E layer
proves the rest: the views are wired to the right state and actions, the real edges work (SwiftData / Room,
UserNotifications / AlarmManager, the system permission dialog, the real clock), and a reminder actually arrives
and can be answered from the notification. It is small, slow and device-bound, so it is not part of `./reflex ci`.

```
./reflex e2e ios        # tools/e2e-ios.sh: iOS 27 Simulator, XCUITest (AppMobileUITests), then tests/reflex-live
./reflex e2e android    # tools/e2e-android.sh: API 37 emulator, androidTest (Compose UI test + UI Automator), then tests/reflex-live
```

## Devices

| | iOS | Android |
|---|---|---|
| Runtime / image | iOS 27 Simulator runtime (`xcodebuild -downloadPlatform iOS`) | `system-images;android-37.0;google_apis;arm64-v8a` + `emulator` (sdkmanager) |
| Device | simulator `Health Beta E2E` (a current iPhone device type), created if missing | AVD `health-beta-api37` (Pixel profile), created if missing; may run `-no-window` |
| Fresh state | `simctl erase` before the run (permissions reset too) | `adb uninstall com.beta.health` / `pm clear` before each journey that needs it |
| Live layer | DEBUG socket on 127.0.0.1:7777 (the simulator shares the host's loopback) | `adb forward tcp:7777 tcp:7777` |

The scripts check prerequisites and print the exact install command when something is missing; they never install
runtimes silently.

## Rules

- Tests tap the UI like a user. They find elements by **accessibility identifiers**, identical on both platforms
  (iOS `.accessibilityIdentifier(_:)`, Android `Modifier.testTag(_:)` with `testTagsAsResourceId = true` so
  UI Automator sees them too). Scheme: `<screen>.<element>[.<id>]`, e.g. `today.addMedication`,
  `today.dose.<doseId>`, `today.dose.<doseId>.take`, `editor.name`, `editor.addTime`, `editor.save`,
  `tab.today` / `tab.medications` / `tab.history`, `reminders.item.<id>`. Text assertions use the contract's
  literals (`docs/health/CONTRACT.md`).
- System UI is driven, not bypassed: the notification permission dialog is answered by tapping it (iOS: springboard
  alert; Android: the permission controller dialog via UI Automator), and notifications are opened from the system
  UI (iOS: springboard banner / Notification Center; Android: `UiDevice.openNotification()`).
- The clock is real. Journeys pick dose times relative to the current time (for example now + 2 minutes) and the
  scripts refuse to start within 10 minutes of local midnight, so a journey never crosses a day boundary.
- No sleeps: wait on conditions with timeouts (`waitForExistence(timeout:)`, `waitUntil`, `UiDevice.wait`).
  Waiting for a scheduled notification may take up to ~150 s; that journey is the only slow one.
- No test-only code paths in the app. The only DEBUG-only piece remains the Reflex socket server.

## Journeys (same on both platforms)

| # | Journey | Proves |
|---|---|---|
| E1 | Fresh install: Today shows "No medications yet". Add a medication (name "Vitamin D", dose "1 capsule", one time a few minutes from now) → Save → the system asks for notification permission → Allow → Today shows the dose as upcoming, no reminder banner | editor wiring, permission flow, SwiftData/Room write |
| E2 | Tap the dose → "Take now" → "1 of 1 taken" and "All done for today"; then Undo → back to "0 of 1 taken" | dose actions and menu wiring |
| E3 | Today → Reminders: "Reminders are on" and an item "Time for Vitamin D" at the chosen time, read back from the OS scheduler | real scheduler round trip |
| E4 | A medication due in about 1 minute: wait for the system notification "Time for …", answer it with **Taken** from the notification, open the app: the dose is taken | delivery, notification actions, cold/warm handling |
| E5 | Terminate and relaunch the app: medications and logs are still there | persistence |
| E6 | Fresh install, add a scheduled medication, **Don't Allow**: banner "Reminders are off" with "Open Settings"; taking a dose still works | denied path |
| E7 | Medications → detail → Archive (moves to Archived) → Restore → Delete → confirm → gone from Medications and Today | lifecycle, destructive confirmation |
| E8 | History: today's entry is listed; previous day shows "Nothing recorded on this day"; next is disabled on today | day pager |
| E9 | Dark appearance and the largest accessibility text size: Today, the editor and the detail render and their main buttons are hittable | appearance, Dynamic Type / font scale |

After the UI journeys, each script runs `tests/reflex-live` (`REFLEX_PLATFORMS=<platform> ./reflex test --live`)
against the running DEBUG build.
