# Health Beta: the Core contract

The shared spec both cores implement (iOS `ios/Packages/AppCore`, Android `android/core`) and the vocabulary of the
behavior tests (`tests/behavior/health-*.cli-test.ts`). Scenarios and their priorities: [SCENARIOS.md](SCENARIOS.md).
If this document and a test disagree, the test wins; fix this document in the same change.

All user-visible strings below are **literals** in the ViewModels, identical on both platforms, ASCII only.

## 1. Domain

### Formats

| Thing | Format | Example |
|---|---|---|
| Local date | `yyyy-MM-dd` | `2026-11-16` |
| Local time | `HH:mm` (24 h, `^([01]\d|2[0-3]):[0-5]\d$`) | `08:00` |
| Local date-time (input) | `yyyy-MM-ddTHH:mm` | `2026-11-16T07:30` |
| Instant (stored, and `fireAt`) | ISO 8601 with seconds and a numeric offset, **never `Z`** | `2026-11-16T08:00:00+09:00`, `2026-11-16T08:00:00+00:00` |
| Weekday | ISO number, 1 = Monday … 7 = Sunday | `[1,3,5]` |

"Today" is the local date of `now` in the clock's current time zone. All schedule times are **floating local
wall-clock times** (they follow the device's current time zone).

**Resolving a local date + time to an instant** (the only allowed rule, implement it explicitly on both platforms):
- the time doesn't exist (spring-forward gap) → shift it forward by the length of the gap (02:30 → 03:30 when
  clocks jump 02:00 → 03:00), i.e. use the offset from *before* the gap (java.time `ZonedDateTime.of` behavior);
- the time exists twice (fall-back overlap) → the *earlier* instant (the offset from before the transition).

An instant is formatted with the offset the current time zone has **at that instant** (`fireAt`), or, for stored
logs, with the offset stored in the log.

### Data (persisted through the `HealthRepository` edge, §2)

Core works on one in-memory value, `HealthData` (medications, logs, snoozes), loaded once and saved after every
change through the `HealthRepository` edge. On the device it is stored with **SwiftData** (iOS) and **Room**
(Android), behind that edge, in the app shells; Core never imports either. In fixtures, `HealthData` is seeded as
JSON in the `storage` section under the key **`health.data`**; the CLI's in-memory repository decodes exactly this
shape (unknown keys ignored, missing optional keys defaulted). The field names below are also the model's field
names on both platforms.

```json
{
  "version": 1,
  "medications": [{
    "id": "vitd", "name": "Vitamin D", "kind": "supplement", "dose": "1 capsule", "notes": "",
    "asNeeded": false, "startDate": "2026-11-01", "endDate": null,
    "schedules": [{"from": "2026-11-01", "times": ["08:00"], "weekdays": [1,2,3,4,5,6,7]}],
    "archived": [{"from": "2026-11-10", "to": null}]
  }],
  "logs": [{
    "id": "l1", "medicationId": "vitd", "date": "2026-11-15", "time": "08:00", "status": "taken",
    "takenAt": "2026-11-15T08:10:00+09:00", "name": "Vitamin D", "dose": "1 capsule"
  }],
  "snoozes": [{"slot": "2026-11-16T08:00", "until": "2026-11-16T09:10:00+09:00"}]
}
```

- `kind`: `"medication"` or `"supplement"`. `dose` and `notes`: free text (may be empty). New ids: random UUIDs,
  lowercase. Ids never contain `@`.
- `schedules`: append-only versions. The version in force on day D is the one with the latest `from ≤ D`; if none
  (D is before every `from`), the earliest one. As-needed medications have `schedules: []`.
- `archived`: periods; `to: null` = still archived. D is archived if `from ≤ D` and (`to` is null or `D ≤ to`).
- Logs: scheduled logs have `date` + `time` (the occurrence they belong to, never the calendar date of `takenAt`);
  as-needed logs have `time: null` and `date` = local date of `takenAt`. `status`: `"taken"` or `"skipped"`
  (`takenAt: null` when skipped). At most one log per (medicationId, date, time). `name`/`dose` are snapshots.
- Nothing stored → empty data. The repository failing to load (fixture: unparseable `health.data`) → `loadError`
  (see Today); Core then never calls `save` and every mutating action is a no-op.

### Occurrences (computed, never stored)

Medication M has an occurrence on day D at time t iff M is not as-needed, `startDate ≤ D`, (`endDate` null or
`D ≤ endDate`), D is not archived, D's weekday is in the version's `weekdays`, and t is in the version's `times`.
The occurrences of a day are those **plus** every scheduled log on that day (a log whose time is no longer in the
schedule still counts: editing never rewrites history).

Dose id: `"<medicationId>@<date>T<time>"`, e.g. `vitd@2026-11-16T08:00`. Its scheduled instant T = resolve(date, time).

### Status

With a log: the log's `status`. Without: `now < T` → `upcoming`; `T ≤ now < T + 120 min` → `due`; otherwise `missed`.

`timing` (taken only): `early` if `takenAt < T − 60 min`, `late` if `takenAt > T + 60 min`, else `onTime`; `null`
for any other status.

### Medication state

- `archived`: has a period with `to: null`. Else `ended`: `endDate < today`. Else `active`.
- Schedule summary (`schedule` fields): as-needed → `"As needed"`; all 7 weekdays → `"Every day at 08:00, 20:00"`;
  otherwise `"Mon, Wed, Fri at 13:00"` (Mon Tue Wed Thu Fri Sat Sun, in weekday order; times ascending).

### Dose actions (shared by Today and History)

| Action | Rule |
|---|---|
| `Take {doseId}` | Logs `taken` at `now`. No-op if already taken, if the id isn't an occurrence (or is a `log:` id). |
| `TakeAt {doseId, takenAt}` | `takenAt` is a local date-time (`yyyy-MM-ddTHH:mm`, resolved in the current zone). Logs or changes `taken` with that time. Works on any status. |
| `Skip {doseId}` | Logs `skipped`. No-op if already skipped. |
| `Undo {doseId}` | Removes the log (status returns to the computed one). For `log:<logId>` (as-needed entries) removes that log. No-op without a log. |

Validation of the taken time (for `Take` too): must be `≤ now` → else `"Time can't be in the future"`; must be
within 12 h of T (`T − 12 h ≤ takenAt ≤ T + 12 h`) → else `"Time must be within 12 hours of the scheduled dose"`;
`TakeAt` with a malformed value → `"Enter a time as yyyy-MM-ddTHH:mm"`. A failing action changes nothing and sets the
screen's `doseError`; the next successful dose action clears it.

### Reminders (computed; Core hands the full set to the scheduler edge)

A **slot** is (date, time); its key is `"<date>T<time>"`. For every slot with at least one occurrence of a
non-archived, scheduled medication that is still **unlogged** (status upcoming/due/missed):

- `T` = resolve(date, time); the slot's doses are its unlogged doses, sorted by name (case-insensitive), then id.
- If the slot has an active snooze (`until > now`): one item `snooze:<slot>` at `until` (kind `snooze`).
- Otherwise: `dose:<slot>` at T (kind `dose`) and `followup:<slot>` at T + 30 min (kind `followUp`).
- Keep only items with `now < fireAt ≤ now + 7 days`. Sort by `fireAt`, then `id` (string order).
- If more than 60 remain: keep the first 59 and append `refresh` at (59th item's fireAt + 1 min), kind `refresh`.
- If notification permission isn't `authorized`, the set is empty.

Content (n = number of doses in the slot; item = `"<name> (<dose>)"`, or `"<name>"` if dose is empty):

| kind | title | body |
|---|---|---|
| dose, snooze | n = 1: `"Time for <name>"`; n > 1: `"Time for <n> medications"` | items joined with `", "` |
| followUp | n = 1: `"Still due: <name>"`; n > 1: `"Still due: <n> medications"` | same |
| refresh | `"Open Health Beta"` | `"Open the app to keep your reminders coming."` |

Notification responses arrive as deep links (§4): **taken** logs every unlogged dose of the slot as taken at now
(doses failing the 12 h rule are left alone); **skipped** logs them skipped; **snooze** sets the slot's snooze to
now + 10 min, unless that is `≥ T + 120 min` (then nothing happens). A slot with no unlogged dose ignores all three.

Core recomputes and hands the set to the scheduler after: the initial load, every data change, every permission
change, every clock change. After handing it over Core reads the scheduler's pending list back; that list is what the
Reminders screen shows.

Permission is requested (only if `notDetermined`) right after the editor saves a medication that has times, and by
`TurnOnReminders`.

## 2. Edges (new)

All three live in `Foundation/Platform.swift` / `foundation/Platform.kt`, are passed to `Core(...)`, and speak the domain.

```swift
// Swift (shape; names are binding, signatures may follow the platform's idioms)
@MainActor public protocol Clock: AnyObject {
    var now: Date { get }
    var timeZone: TimeZone { get }
    /// Yields on minute ticks, significant time changes, time zone changes, app foreground. Never in fixtures.
    var changes: AsyncStream<Void> { get }
}
public enum NotificationPermission: String, Sendable { case notDetermined, denied, authorized }
public struct Reminder: Sendable, Equatable {
    public let id: String; public let kind: String; public let fireAt: Date
    public let localFireTime: String   // resolved local wall time "yyyy-MM-ddTHH:mm" (for floating OS triggers)
    public let title: String; public let body: String; public let slot: String?   // nil for refresh
}
public protocol ReminderScheduler: Sendable {
    func permission() async -> NotificationPermission
    func requestPermission() async -> NotificationPermission
    func mayBeLate() async -> Bool            // Android without exact alarms; always false on iOS
    func replaceAll(_ reminders: [Reminder]) async   // pending := reminders (diff by id; one call at a time)
    func pending() async -> [Reminder]
    func openSettings() async
}
```

```swift
/// Persistence of the Health data. App: SwiftData (`@Model` records, `@ModelActor`, off the main actor).
/// CLI: in memory, seeded from the fixture's `storage["health.data"]`.
public protocol HealthRepository: Sendable {
    func load() async throws -> HealthData            // throws when the store can't be read
    func save(_ data: HealthData) async throws        // full snapshot; upsert/delete by id in one transaction
}
```

Kotlin: `interface HealthRepository { suspend fun load(): HealthData; suspend fun save(data: HealthData) }`
(app: Room `@Database`/`@Entity`/`@Dao` with a `@Transaction` replace, on `Dispatchers.IO`; CLI: in memory).
`HealthData` and its parts are plain Core types (Swift `Sendable` structs, Kotlin data classes). Saves are
serialized (one at a time, in order) and never block the UI thread.

Kotlin: `interface Clock { fun now(): Instant; fun zone(): ZoneId; val changes: Flow<Unit> }` (java.time, coroutines),
`enum class NotificationPermission { NotDetermined, Denied, Authorized }`, `data class Reminder(...)`,
`interface ReminderScheduler` with `suspend` functions.

**Fixture keys** (in the existing `device` section, no new top-level key; same defaults on both hosts):

| Key | Meaning | Default |
|---|---|---|
| `now` | fixed current local date-time `yyyy-MM-ddTHH:mm:ss` in `timeZone` (resolved with the rule above) | `"2026-01-01T00:00:00"` |
| `timeZone` | IANA id | `"UTC"` |
| `notificationPermission` | `notDetermined` / `denied` / `authorized` | `"notDetermined"` |
| `notificationRequestResult` | what the user answers when asked: `authorized` / `denied` | `"denied"` |
| `remindersMayBeLate` | bool | `false` |

Fixture adapters: a fixed clock whose `changes` never yields; an in-memory scheduler (`replaceAll` stores, `pending`
returns them in the order given, `requestPermission` switches `notDetermined` to the request result and returns the
current status, `openSettings` does nothing).

## 3. Screens (nodes)

Navigation tree: `App › TabNavigator › StackNavigator › <screen>`. `TabNavigator` (Foundation, both platforms, the
contract in `reflex-new-screen`): tabs `["today", "medications", "history"]`, initially `today`, each tab its own
`StackNavigator` with root `Today` / `Medications` / `History`. It lends `selectTab {tab}` (unknown id: no-op);
`StackNavigator` lends `goBack`. Screens are pushed on the **current** tab's stack.

State fields are listed **in order** (node JSON key order). `dose` objects (Today, History, MedicationDetail):

```
{id, medicationId, name, dose, date, time, status, takenAt, timing, canTake, canSkip, canUndo}
```

`takenAt` = `"HH:mm"` in the offset stored in the log, or null. `canTake` = status ≠ taken; `canSkip` = status ≠
skipped; `canUndo` = has a log. As-needed log entries use the same shape: `id: "log:<logId>"`, `time: null`,
`status: "taken"`, `timing: null`, `canTake: false`, `canSkip: false`, `canUndo: true`. `name`/`dose`: the log's
snapshot when logged, else the medication's current values.

### Today (root of `today`)

| State | |
|---|---|
| `isLoading` | true until the stored data is loaded |
| `date` | today |
| `progressLabel` | `"<taken> of <scheduled> taken"` over occurrences dated today; null when there are none |
| `takenCount`, `scheduledCount` | the two numbers |
| `message` | first that applies (null while loading): load failed `"Couldn't load your data"`; no active medication at all `"No medications yet"`; no occurrence today `"Nothing scheduled today"`; every occurrence today taken or skipped `"All done for today"`; else null |
| `reminderBanner` | null unless some active medication has times. Then: permission denied `"Reminders are off"`; notDetermined `"Turn on reminders to get notified"`; authorized and mayBeLate `"Reminders may arrive a few minutes late"`; else null |
| `groups` | `[{key, date, time, doses: [dose]}]`: today's occurrences **plus** earlier days' occurrences whose `T + 120 min > now` (any status). Grouped by slot, sorted by date then time; doses by name (case-insensitive), then medication id |
| `asNeeded` | active as-needed medications by name: `{medicationId, name, dose, takenToday, lastTakenAt, summary}`; `summary`: `"Not taken today"`, `"Taken once today, last at 08:30"`, `"Taken 2 times today, last at 09:00"` |
| `doseError` | see dose actions |

Actions: `Take {doseId}`, `TakeAt {doseId, takenAt}`, `Skip {doseId}`, `Undo {doseId}`,
`LogAsNeeded {medicationId}` (log at now; no-op for unknown/non-as-needed), `UndoAsNeeded {medicationId}` (removes
today's latest as-needed log of it; no-op if none), `TurnOnReminders` (notDetermined → request; denied, or
authorized but mayBeLate → open settings; otherwise no-op), `OpenReminders` (push Reminders), `AddMedication` (push
MedicationEditor in add mode), `OpenMedication {id}` (push MedicationDetail).

### Medications (root of `medications`)

State: `isLoading`, `isEmpty` (no medication at all), `active`, `ended`, `archived`: each
`[{id, name, kind, dose, schedule}]` sorted by name (case-insensitive).
Actions: `Add` (push editor, add mode), `Open {id}` (push detail).

### MedicationEditor

State: `mode` (`"add"`/`"edit"`), `medicationId` (null when adding), `name`, `kind`, `dose`, `notes`, `asNeeded`,
`times` (ascending), `weekdays` (ascending), `startDate`, `endDate`, `nameError`, `timesError`, `weekdaysError`,
`datesError`, `timeError`, `canAddTime`, `canSave`, `isSaving`.

Add defaults: name/dose/notes `""`, kind `"medication"`, asNeeded false, times `[]`, weekdays `[1..7]`,
startDate today, endDate null. Edit: the medication's values with the times/weekdays in force today.

Actions: `SetName {name}`, `SetKind {kind}` (other values: no-op), `SetDose {dose}`, `SetNotes {notes}`,
`SetAsNeeded {asNeeded}`, `AddTime {time}`, `RemoveTime {time}`, `ToggleWeekday {weekday}` (outside 1–7: no-op),
`SetStartDate {date}`, `SetEndDate {date: string|null}` (malformed dates: no-op), `Save`.

Validation (the four `*Error` fields stay null until the first `Save` attempt, then update live):
- trimmed name empty → `nameError` `"Name is required"`; equal (trimmed, case-insensitive) to another non-archived
  medication's name → `"A medication with this name already exists"`.
- not as-needed and no times → `timesError` `"Add at least one time"`.
- not as-needed and no weekdays → `weekdaysError` `"Pick at least one day"`.
- endDate before startDate → `datesError` `"End date can't be before start date"`.
- As-needed: times/weekdays are not validated (and are saved empty).

`timeError` is immediate and only from `AddTime`: malformed → `"Enter a time as HH:mm"`; already in the list →
`"Each time can only be added once"`; it clears on the next successful `AddTime`/`RemoveTime`. `canAddTime` = fewer
than 12 times (`AddTime` beyond that: no-op). `canSave` = valid, not saving, and the stored data loaded without
error.

`Save`: invalid → shows errors, nothing saved. Valid → stores (name trimmed); in edit mode a changed
times/weekdays adds a schedule version from today (replacing a version that already starts today); then pops the
editor and, if the medication has times and permission is notDetermined, requests permission.

### MedicationDetail

State: `id`, `name`, `kind`, `dose`, `notes`, `schedule`, `startDate`, `endDate`, `status`
(`active`/`ended`/`archived`), `adherenceLabel` (`"<p>%"` or `"No data yet"`), `takenCount`, `skippedCount`,
`missedCount`, `history`, `canArchive`, `canRestore`, `isConfirmingDelete`.

- Window: the 30 days ending today. Counts over its occurrences with status taken/skipped/missed. Adherence
  p = round-half-up(100 × taken / (taken + skipped + missed)) = `(200·taken + d) / (2·d)` in integer arithmetic.
- `history`: dose objects in the window whose status isn't `upcoming`, plus as-needed log entries, newest first
  (date desc, then time desc, using `takenAt` for as-needed entries).

Actions: `Edit` (push editor, edit mode), `Archive` (adds `{from: today, to: null}`; no-op if archived), `Restore`
(closes the open period with `to` = yesterday, dropping it if that is before its `from`; no-op if not archived),
`Delete` (sets `isConfirmingDelete`), `CancelDelete`, `ConfirmDelete` (removes the medication and all its logs, pops;
no-op unless confirming).

### History (root of `history`)

State: `date` (initially today), `canGoNext` (date < today), `summaryLabel` (`"<taken> of <scheduled> taken"` over
that day's occurrences; null if none), `doses` (that day's occurrences plus its as-needed log entries, sorted by
time, `takenAt` for as-needed entries, then name), `message` (`"Nothing recorded on this day"` when `doses` is
empty, else null), `doseError`.
Actions: `PreviousDay`, `NextDay` (no-op at today), `SelectDate {date}` (future or malformed: no-op), `Take`,
`TakeAt`, `Skip`, `Undo` (the dose actions).

### Reminders (pushed from Today)

State: `permission` (`notDetermined`/`denied`/`authorized`), `mayBeLate`, `statusLabel`, `reminders`
(`[{id, kind, fireAt, title, body}]`, the scheduler's pending list).
`statusLabel`: authorized `"Reminders are on"`, or `"Reminders may arrive a few minutes late"` when mayBeLate;
denied `"Reminders are off. Turn them on in Settings."`; notDetermined `"Reminders aren't set up yet"`.
Actions: `TurnOnReminders` (as on Today).

## 4. Deep links (scheme-agnostic, `AppRoot.open`)

| Link | Effect |
|---|---|
| `<s>://today` | select `today`, reset its stack to `[Today]` |
| `<s>://medication/<id>` | select `medications`, reset to `[Medications, MedicationDetail(id)]` |
| `<s>://reminder/<slot>` | (tap on a notification) select `today`, reset to `[Today]` |
| `<s>://reminder/<slot>/taken` · `/skipped` · `/snooze` | the same, then the notification action (§1 Reminders) |

Links may arrive before the stored data has loaded (the CLI opens them right after `Core(...)`, the app on a cold
start from a notification): actions are applied once loading has finished.

## 5. Concurrency

- Swift 6 language mode (complete strict concurrency checking), iOS 27. Swift Concurrency only: `async`/`await`,
  `actor` (the SwiftData repository is a `@ModelActor`), `@MainActor` for ViewModels and the store, `Sendable`
  value types across isolation boundaries, `AsyncStream` for event sources, `Task` for side effects started from
  `update`. No GCD (`DispatchQueue`), no completion handlers, no `@unchecked Sendable`, no `nonisolated(unsafe)`,
  no new locks. Platform APIs are used through their async forms (`UNUserNotificationCenter` async methods).
- Kotlin: coroutines and `Flow` the same way (one core thread / `Dispatchers.Main`, I/O on `Dispatchers.IO`).
  Android 17 (API 37) is both `minSdk` and `targetSdk`.

## 6. Suggested Core structure (same on both platforms, so parity bugs are easy to locate)

`Features/Health/` (`features/health/`):
- `HealthModel` – data types, JSON coding of `health.data`, pure functions: occurrences, status, timing,
  schedule summary, dose objects, adherence, time resolution/formatting.
- `ReminderPlanner` – pure: (data, now, zone, permission) → `[Reminder]`.
- `HealthStore` – the single owner of the data: loads once through `HealthRepository`, applies mutations
  synchronously on the main actor / core thread, saves asynchronously (serialized), notifies subscribers; runs the planner and the scheduler
  (`replaceAll` then `pending`, serialized) and publishes the permission / mayBeLate / pending list.
- One ViewModel per screen subscribing to the store; store snapshots arrive as Messages, so `update` stays the only
  writer of screen state. Dose actions call store methods from `update` and the result shows up as a snapshot.
