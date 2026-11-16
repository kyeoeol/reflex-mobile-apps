# Health Beta: end-to-end tests on simulators and emulators

The behavior suite (`tests/behavior`) proves the cores; it never renders a view or touches the OS. The E2E layer
proves the rest: the views are wired to the right state and actions, the real edges work (SwiftData / Room,
UserNotifications / AlarmManager, the system permission dialog, the real clock), and a reminder actually arrives
and can be answered from the notification. It is small, slow and device-bound, so it is not part of `./reflex ci`.

```
./reflex e2e ios        # tools/e2e-ios.sh: iOS 27 Simulator, XCUITest (AppMobileUITests), then tests/reflex-live
./reflex e2e android    # tools/e2e-android.sh: API 37 emulator, the self-instrumenting :e2e module (UI Automator), then tests/reflex-live
```

## Devices

| | iOS | Android |
|---|---|---|
| Runtime / image | iOS 27 Simulator runtime (`xcodebuild -downloadPlatform iOS`) | `system-images;android-37.0;google_apis;arm64-v8a` + `emulator` (sdkmanager) |
| Device | simulator `Health Beta E2E` (a current iPhone device type), created if missing | AVD `health-beta-api37` (Pixel profile), created if missing; may run `-no-window` |
| Fresh state | `simctl erase` before the run (permissions reset too) | `adb uninstall com.beta.health` / `pm clear` before each journey that needs it |
| Live layer | DEBUG socket on 127.0.0.1:7777 (the simulator shares the host's loopback) | `adb forward tcp:$REFLEX_LIVE_PORT tcp:7777` (default 7777; set `REFLEX_LIVE_PORT` when the iOS DEBUG app holds 7777 on the Mac) |
| UI tests run as | `xcodebuild test-without-building` on the `AppMobileUITests` target | `am instrument` of the `:e2e` module (installed with `adb`; not `connectedAndroidTest`, which grants every runtime permission and uninstalls the app) |

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

## Accessibility identifiers (identical on both platforms; the UI tests use only these and contract literals)

`<id>` placeholders are the contract's ids (`doseId`, `medicationId`, reminder `id`, `HH:mm`, weekday 1-7).

| Screen | Identifiers |
|---|---|
| Tab bar | `tab.today`, `tab.medications`, `tab.history` |
| Today | `today.date`, `today.progress`, `today.message`, `today.retry`, `today.banner`, `today.banner.action`, `today.doseError`, `today.openReminders`, `today.addMedication`, `today.group.<key>` |
| Dose rows (Today: prefix `today`, History: prefix `history`, Detail history: prefix `detail`) | `<prefix>.dose.<doseId>` (the row; tap opens the dose menu), `<prefix>.dose.<doseId>.status` (status text), `<prefix>.dose.<doseId>.take`, `<prefix>.dose.<doseId>.skip` (inline buttons, only when `needsAction`) |
| Dose menu | `doseMenu.take`, `doseMenu.takeAt`, `doseMenu.skip`, `doseMenu.undo` |
| Take-at sheet | `takeAt.picker`, `takeAt.save`, `takeAt.cancel` |
| As-needed rows | `today.asNeeded.<medicationId>`, `.summary`, `.log`, `.undo` (suffixes on that id) |
| Medications | `medications.add`, `medications.empty`, `medications.section.active`, `medications.section.ended`, `medications.section.archived`, `medications.item.<medicationId>` |
| Editor | `editor.name`, `editor.kind.medication`, `editor.kind.supplement`, `editor.dose`, `editor.notes`, `editor.asNeeded`, `editor.addTime`, `editor.time.<HH:mm>`, `editor.time.<HH:mm>.remove`, `editor.weekday.<1-7>`, `editor.startDate`, `editor.endDate`, `editor.endDate.clear`, `editor.save`, `editor.error.name`, `editor.error.times`, `editor.error.weekdays`, `editor.error.dates`, `editor.error.time` |
| Time picker (editor) | `editor.timePicker` (iOS wheel), `editor.timePicker.hour`, `editor.timePicker.minute` (Android: two 24 h digit fields starting at 08:00; Material3 `TimeInput` can't tag its inner fields), `editor.timePicker.confirm`, `editor.timePicker.cancel` |
| Detail | `detail.name`, `detail.schedule`, `detail.status`, `detail.adherence`, `detail.edit`, `detail.archive`, `detail.restore`, `detail.delete` |
| Delete dialog | `deleteDialog.confirm`, `deleteDialog.cancel` |
| History | `history.date` (opens the date picker), `history.previous`, `history.next`, `history.summary`, `history.message`, `history.doseError` |
| Reminders | `reminders.status`, `reminders.turnOn`, `reminders.item.<id>` |

Elements whose state the tests read expose it as accessibility value / text (status text, progress, messages);
buttons expose their enabled state.

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

Grouping by fresh-state needs (each group starts from a fresh install): **E1+E2+E3+E5**, **E4**, **E6**,
**E7+E8**, **E9**. iOS runs E6 in a second `test-without-building` pass after `simctl uninstall`; Android uses a
self-instrumenting test module (`com.android.test`, UI Automator only, elements by resource id) so a journey can
kill and relaunch the app: kill with Home then `am kill` (never `force-stop`, which clears alarms).

E4 recipe: fire time = next minute boundary + 2 minutes; terminate the app before it fires; wait up to 240 s for the
notification and find it by title in Notification Center / the notification shade (the transient banner disappears
after a few seconds); answer **Taken**; relaunch and wait for "1 of 1 taken". Time pickers must be drivable (iOS
wheel, Android `TimeInput` mode).

**The one exception to "grant nothing up front":** on Android the script grants exact alarms after install
(`appops set com.beta.health SCHEDULE_EXACT_ALARM allow`), because API 37 denies `SCHEDULE_EXACT_ALARM` by default and
a 10-minute inexact window would make E1's "no banner" and E4's timing fail. The "may be late" path is covered by the
headless behavior tests.

After the UI journeys, each script runs `tests/reflex-live` (`REFLEX_PLATFORMS=<platform> ./reflex test --live`)
against the running DEBUG build.
