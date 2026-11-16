# Health Beta — mobile design system

Health Beta is a local medication and supplement ledger with dose reminders. Use a quiet canvas, large system type, small metadata, hairline dividers, and pill controls. No accounts, remote content, treatment advice, or health-outcome charts. All examples are fictional.

This handoff follows [the Core contract](../health/CONTRACT.md) **plus the advisor amendments supplied for this design task**. The contract is being revised independently: its older `canSave` rule, missing presentation flags, and missing captions must not be copied into views. Components read values and dispatch only the owning screen's actions. Core owns status, validation, schedules, permission decisions, navigation, and persistence. Components never reconstruct those rules.

[Mockups](mockups.html) provide paired light/dark 390 × 844 review frames, scrollable content, pristine and attempted-save editor examples, day paging/date picker intent, dose menu intent, error states, detail lifecycle actions, and Reminders. HTML interactions illustrate native UI; they are not application behavior. Native menus, pickers, and dialogs take precedence over the HTML facsimiles.

## Visual language

Use SF Pro via system styles on iOS and the system family on Android. No bundled fonts. Left alignment, one column, 20pt/dp side gutters, section gaps 24–32, row padding 16, label/value gap 8. Respect safe areas, keyboard insets, RTL, system appearance, Reduce Motion, Dynamic Type, and Android font scale. Content scrolls; no fixed text heights, truncation, or ellipsis for names involved in dose actions. The heart gradient is reserved for the app icon/optional small wordmark.

**Times remain the supplied 24-hour `HH:mm` strings everywhere**, including groups, schedules, selected editor times, taken times, and reminder times. Never use locale time formatting or an AM/PM picker. Native time pickers must be configured for 24-hour output. Only dates are locale formatted; `SelectDate` and editor date actions still receive `yyyy-MM-dd`, and `TakeAt` receives `yyyy-MM-ddTHH:mm`. Render supplied group `label` verbatim (including `Yesterday, 23:30`), rather than computing day-part or relative-date suffixes.

### Semantic colors

Swift uses `HealthColors`; Android uses `HealthTheme.colors`. Values are sRGB.

| Token | Light | Dark | Use |
|---|---|---|---|
| background | #FFFFFF | #000000 | Canvas, safe areas, tabs |
| secondaryBackground | #F2F2F2 | #141414 | Quiet panels |
| tertiaryBackground | #EBEBEB | #262626 | Progress track |
| inputBackground | #F2F2F2 | #121212 | Fields |
| primary | #000000 | #FFFFFF | Text, primary fill, focus |
| onPrimary (Android: background) | #FFFFFF | #000000 | Primary button label |
| secondary | #666666 | #999999 | Metadata, placeholders |
| tertiary | #B3B3B3 | #4D4D4D | Decoration only |
| body | #474747 | #B8B8B8 | Supporting text |
| border | #E6E6E6 | #1A1A1A | Decorative hairlines |
| strongBorder | #CCCCCC | #333333 | Labeled button outlines |
| controlBorder | #8C8C8C | #757575 | Text-field, select, unselected toggle outlines |
| buttonSecondary | #F2F2F2 | #1A1A1A | Secondary surfaces |
| success / successBackground | #007A51 / #DBF8EB | #53D6A3 / #002218 | Taken chip |
| destructive / destructiveBackground | #BF1725 / #FEDEE3 | #FF7C86 / #3D0105 | Errors, missed chip, Delete |
| brandTop → brandBottom | #FF61AD → #FF2719 | Same | Heart only |

`controlBorder` has at least 3:1 contrast against both the field and canvas in each theme (light: 3.004:1 on #F2F2F2; dark: 4.07:1 on #121212). Faint dividers are decorative, never field boundaries. Native focus uses primary foreground. State always has a textual label as well as an icon; red never carries meaning alone.

### Type and geometry

| Token | iOS | Android size / line height |
|---|---|---|
| display | 48pt, UIFontMetrics scaled | 48sp / 52sp |
| title | largeTitle medium | 32sp / 36sp |
| section | title2 medium | 22sp / 28sp |
| row | body medium | 16sp / 24sp |
| body | body regular | 16sp / 24sp |
| smallBody | subheadline | 14sp / 22.4sp |
| meta | footnote medium | 13sp / 20sp, +0.52sp tracking |
| caption | footnote | 13sp / 20sp |
| input | body | 15sp / 22sp |
| footnote | caption2 | 11sp / 14sp |

Controls have minimum 48pt/dp width and height, exceeding iOS's 44pt minimum. Minimums grow with content; no maximum line count or fixed button height. Action groups and top bars wrap/stack at larger text sizes. Pill/capsule controls, 16-radius panels and multiline fields, 24-radius dialogs. Hairlines are one physical pixel. Floating Add has a 6pt/dp canvas halo; reserve 80pt/dp below scrolling content, or move Add into normal flow at large type.

Every interactive element has a label. Decorative Android icons are `ImageVector`s in `Icon(..., contentDescription = null)`; icon-only Previous/Next controls have explicit descriptions. iOS decorative SF Symbols are hidden from accessibility. Fields expose persistent labels and current values. **iOS field errors are appended to the accessibility value and announced on change**, never put in a hint. Android exposes field error semantics and polite live-region captions. Inline dose errors use a polite announcement, remain visible above the list, and clear only when state clears them.

Weekdays are **Mo Tu We Th Fr Sa Su**. Use `WeekdayPicker` with full spoken names and `PillToggle` toggle semantics (`.isToggle` on iOS, `Role.Switch`/`toggleable` on Android). Grid cells have equal widths, at least 48pt/dp targets, and wrap into fewer columns at narrow widths or larger font sizes. Do not shrink text or fit seven undersized targets across a phone.

## Component behavior

- `PillButton`: primary fill for Save/Taken/Add; outline for Skip/Edit/Archive/Restore; destructive treatment for Delete. Enabled values come from state; a disabled Save must never be inferred from validity.
- `PillSelect`: persistent semantic label plus displayed value and decorative down chevron. Uses `controlBorder`. Opens a host-owned native picker; no unit chooser exists.
- `HealthTextField`: one value and optional literal error, normal or multiline. Dose is optional free text, label **Dose**, placeholder **e.g. 500 mg**. No quantity parsing, unit menu, numeric validation, or dose error. Notes are multiline.
- `PillSegmentedControl`: kind labels Medication/Supplement, selected index mapped from `kind`. It owns no selection. `PillToggle` also serves the As needed control, disabled when `canChangeAsNeeded` is false.
- `MetaRow`, `ListRow`, `SectionHeader`: text/divider primitives; ordinary medication rows can be wrapped in a labeled host Button with a 48pt/dp minimum. Do not nest interactive elements.
- `StatusChip`: upcoming → clock/Upcoming, due → dot/Due, taken → check/Taken, skipped → minus/Skipped, missed → exclamation/Missed. This is a visual enum mapping of the supplied status, not a time-based calculation.
- `DoseRow`: whole row opens a native menu. Menu entries are **Take now**, **Change time…**, **Skip**, **Undo**, individually shown by `canTake`, `canTakeAt`, `canSkip`, `canUndo`. The row's menu trigger and its inline buttons are siblings. Inline **Taken** and **Skip** appear only inside `needsAction`, further gated by `canTake`/`canSkip`. Never infer eligibility from status, date, or whether a log exists. Change time opens a native date/time picker, dispatching `TakeAt` when committed; picker cancellation dispatches no action. Take now and Taken both dispatch `Take`. A taken/upcoming/skipped row can still have menu actions if state permits. Show name, dose, time/date, status, optional supplied timing/takenAt; never medication kind text.
- `AsNeededRow`: name, optional dose, literal `summary`, Log taken, and Undo only when `canUndo`. Dispatch `LogAsNeeded` / `UndoAsNeeded`; do not infer from `takenToday`.
- `ProgressLine`: label is state copy. The host may map supplied counts to a presentation fraction without recounting rows; omit when the label is null. Never define adherence/status rules here.
- `EmptyStateView`: literal message, optional supplied title/action. Avoid inventing explanatory copy to fill an empty panel. Today uses `messageKind` for icon/treatment/Retry selection, never matches message text or infers from list counts.
- `Banner`: title plus optional message/action. Today passes `reminderBanner` and `reminderBannerAction` directly; no extra explanation. Null action means no button. All banner actions dispatch `TurnOnReminders`; Core chooses request/settings.
- `InlineErrorBanner`: literal `doseError` above the first row. No timed feedback overlay.
- `HistoryDayPager`: Previous chevron, tappable date with date-picker affordance, Next chevron enabled by `canGoNext`. No week strip or range selector. Host owns only picker presentation, not selected-day business state.
- `ReminderRow`: pending reminder `kind`, date plus 24-hour time from `fireAt`, literal `title` and `body`. No recomputed schedule or delivery promise.
- `HealthConfirmationDialog`: native Delete confirmation only, controlled by `isConfirmingDelete`. Use fixed chrome “Delete medication?”, “This removes the medication and its dose history. This cannot be undone.”, Delete, Cancel. Dismiss dispatches `CancelDelete`; confirm dispatches `ConfirmDelete`. Restore focus to Delete. Archive is immediate, with no dialog. Cancel/back in the editor uses the navigator's `goBack`; no discard dialog.

## Screen specifications and State → component mapping

Static screen titles, field labels, action names, and section headings are presentation chrome. Dynamic values and conditional content below are exclusively screen state. Null messages/banners/labels are omitted. Loading uses a native labeled progress indicator, never an invented empty state. Root tabs (Today/Medications/History) read the parent navigator and dispatch its `selectTab`; back reads/routes the parent stack via `goBack`. Only those containers lend navigation actions.

### Today

Health Beta top bar with bell + **Reminders** → `OpenReminders`. Large Today, locale-formatted `date`, optional progress, literal message, optional reminder banner, `doseError`, groups in supplied order, then As needed. Groups use `label` exactly, without a day-part suffix. No extra completed-state heading or caption.

`messageKind`: `loadError` shows `message` and Retry → `Retry`; `saveError` shows the literal message as a persistent error panel; `noMedications` shows `message` and Add medication → `AddMedication`; `nothingToday` shows `message` while preserving As needed; `allDone` is a quiet message with existing rows; null adds no message panel. The revised Core supplies literal `message` for every case. Never decide “done” from counts.

| Component | Exact Today state fields read | Actions |
|---|---|---|
| Loading/date | `isLoading`, `date` | — |
| ProgressLine | `progressLabel`, `takenCount`, `scheduledCount` | — |
| EmptyStateView / message panel | `message`, `messageKind` | `Retry`, `AddMedication` as above |
| Banner | `reminderBanner`, `reminderBannerAction` | `TurnOnReminders` |
| InlineErrorBanner | `doseError` | — |
| Group heading | `groups[].label` (`key` for identity) | — |
| DoseRow | `groups[].doses[].id`, `name`, `dose`, `date`, `time`, `status`, `timing`, `takenAt`, `needsAction`, `canTake`, `canTakeAt`, `canSkip`, `canUndo` | `Take`, `TakeAt`, `Skip`, `Undo` with `doseId: id` |
| AsNeededRow | `asNeeded[].medicationId`, `name`, `dose`, `summary`, `canUndo` | `LogAsNeeded`, `UndoAsNeeded` with `medicationId` |
| Reminders top-bar button | No screen field; static chrome | `OpenReminders` |

### Medications

Large Medications. Active, **Ended**, Archived sections, preserving Core ordering. Row name, dose, kind, and literal `schedule`; no derived frequency. Floating Add → `Add`; row → `Open {id}`. `isEmpty` permits the empty-state treatment with Add only, without an invented dynamic title or body.

| Component | Exact Medications state fields read | Actions |
|---|---|---|
| Loading/empty treatment | `isLoading`, `isEmpty` | `Add` |
| SectionHeader + ListRow host buttons | `active[]`, `ended[]`, `archived[]`: `id`, `name`, `kind`, `dose`, `schedule` | `Open {id}` |
| FloatingPillButton | No screen field | `Add` |

### MedicationEditor

Full-screen scrolling form; Cancel at top, persistent keyboard-aware Save at bottom, no tabs. Name, Kind, **Dose**, As needed, scheduled Times and Days when `asNeeded` is false, Start date, optional End date, Notes. Times are supplied sorted 24-hour strings, with labeled remove buttons and Add time enabled by `canAddTime`. Save dispatches `Save` and is enabled solely by `canSave`. Save remains tappable on a pristine form. `isValid` drives **nothing visual**. The four validation errors remain absent until the first Save attempt; display only supplied errors, retaining entered values. `timeError` can appear immediately after AddTime; do not suppress it behind Save. Disable As needed using `canChangeAsNeeded` (false in edit mode), without deriving this from `mode`.

Literal errors: `Name is required`; `A medication with this name already exists`; `Add at least one time`; `Pick at least one day`; `End date can't be before start date`; `Enter a time as HH:mm`; `Each time can only be added once`. No other validation copy. No field errors on the pristine mockup.

| Component | Exact MedicationEditor state fields read | Actions |
|---|---|---|
| Title | `mode` | — |
| Name HealthTextField | `name`, `nameError` | `SetName {name}` |
| Kind PillSegmentedControl | `kind` | `SetKind {kind}` |
| Dose HealthTextField | `dose` | `SetDose {dose}` |
| As needed PillToggle | `asNeeded`, `canChangeAsNeeded` | `SetAsNeeded {asNeeded}` |
| Time selects / remove / Add time / error captions | `asNeeded`, `times`, `canAddTime`, `timesError`, `timeError` | `AddTime {time}`, `RemoveTime {time}` |
| WeekdayPicker / error caption | `asNeeded`, `weekdays`, `weekdaysError` | `ToggleWeekday {weekday}` |
| Date PillSelects / error caption | `startDate`, `endDate`, `datesError` | `SetStartDate {date}`, `SetEndDate {date: string|null}` |
| Notes HealthTextField | `notes` | `SetNotes {notes}` |
| Save button / saving indicator | `canSave`, `isSaving` | `Save` |

### MedicationDetail

Edit in the top bar → `Edit`. Name, kind, dose, status, literal schedule, date range, optional notes. Adherence uses **`adherenceLabel`** and **`adherenceCaption`** exactly (e.g. `6%`, `2 of 31 doses taken in the last 30 days`). No seven-day caption, recalculated denominator, or fabricated completion claim.

Place outline Archive when `canArchive`, outline Restore when `canRestore`, and destructive Delete **above Recent history**. Archive → `Archive` immediately. Restore → `Restore`. Delete → `Delete`, which sets `isConfirmingDelete` and presents the native dialog. Recent history is read-only on this screen: its contract has no `Take`/`TakeAt`/`Skip`/`Undo` actions. Do not use interactive DoseRow here; use ListRow + StatusChip with no menu or quick buttons.

| Component | Exact MedicationDetail state fields read | Actions |
|---|---|---|
| Heading / metadata / schedule / dates / notes | `name`, `kind`, `dose`, `status`, `schedule`, `startDate`, `endDate`, `notes` | `Edit` |
| Adherence labels | `adherenceLabel`, `adherenceCaption` | — |
| Lifecycle buttons | `canArchive`, `canRestore` | `Archive`, `Restore`, `Delete` |
| HealthConfirmationDialog | `isConfirmingDelete` | `ConfirmDelete`, `CancelDelete` |
| Recent history ListRow + StatusChip | `history[].id`, `name`, `dose`, `date`, `time`, `takenAt`, `status`, `timing` | — |

### History

One day at a time. Large History; `HistoryDayPager` with Previous → `PreviousDay`, date → native date picker → `SelectDate {date}`, Next → `NextDay` enabled by `canGoNext`. Display `summaryLabel`, `doseError`, then doses in supplied order. Empty content is exactly `message`: `Nothing recorded on this day`. The date picker has no view-computed future-date bound: Core rejects a future `SelectDate`. A host must not obtain its own clock to enforce this rule. Dismissed pickers send nothing. No History-to-detail row navigation; rows open dose actions just as Today does.

| Component | Exact History state fields read | Actions |
|---|---|---|
| HistoryDayPager | `date`, `canGoNext` | `PreviousDay`, `NextDay`, `SelectDate {date}` |
| Summary / empty message | `summaryLabel`, `message` | — |
| InlineErrorBanner | `doseError` | — |
| DoseRow | `doses[].id`, `name`, `dose`, `date`, `time`, `takenAt`, `status`, `timing`, `needsAction`, `canTake`, `canTakeAt`, `canSkip`, `canUndo` | `Take`, `TakeAt`, `Skip`, `Undo` with `doseId: id` |

For as-needed `log:` entries use the same supplied flags; no special view-side permission rule. A null `time` means omit the scheduled-time label and show `takenAt` when present.

### Reminders

Pushed from Today's bell button; back returns through the Today stack. Large Reminders, literal `statusLabel`, outline **Turn on reminders** → `TurnOnReminders`, pending list of `ReminderRow`. Keep the button available; Core chooses permission request/settings/no-op. `permission` and `mayBeLate` never generate extra explanatory text, disable logging, or independently select settings actions. No inferred empty-list message, countdown, or delivery guarantee. No platform permission calls from components.

| Component | Exact Reminders state fields read | Actions |
|---|---|---|
| Status text | `statusLabel` | — |
| Turn on reminders button | No field needed; `permission` / `mayBeLate` intentionally do not drive separate UI | `TurnOnReminders` |
| ReminderRow | `reminders[].id`, `kind`, `fireAt`, `title`, `body` | — |

## API handoff

Components are internal, presentational, and depend on no Core/feature types. Hosts map the exact fields above to primitive arguments and callbacks. Local menu/picker visibility is UI presentation only; persisted state, confirmation visibility, and all eligibility flags belong to Core. `onTakeAt` opens a native picker and dispatches only on commit. `onSelectDate` opens a native date picker; no date arithmetic in the pager.

### iOS component API

Defaulted values are shown. `DoseStatusKind`: upcoming/due/taken/skipped/missed; `PillButtonKind`: primary/outline/destructive.

```swift
PillButtonStyle(kind: PillButtonKind = .primary, isControl: Bool = false,
                horizontalPadding: CGFloat = 20) // Button.disabled(!state.canX)
HairlineDivider()
MetaRow(label: String, detail: String? = nil, symbol: String? = nil)
ListRow(title: String, meta: String, detail: String? = nil)
SectionHeader(title: String, subtitle: String? = nil)
StatusChip(kind: DoseStatusKind)
ProgressLine(fraction: Double, label: String)
EmptyStateView(message: String, title: String? = nil, symbol: String = "pills",
               actionTitle: String? = nil, onAction: () -> Void = {})
FloatingPillButton(title: String, symbol: String = "plus", onTap: () -> Void)
Banner(title: String, message: String? = nil, actionTitle: String? = nil,
       onAction: () -> Void = {})
PillSelect(label: String, title: String, onTap: () -> Void)
PillSegmentedControl(labels: [String], selectedIndex: Int, onSelect: (Int) -> Void)
PillToggle(title: String, isOn: Bool, isEnabled: Bool = true,
           accessibilityTitle: String? = nil, onToggle: () -> Void)
WeekdayPicker(selectedWeekdays: [Int], onToggle: (Int) -> Void)
HealthTextField(label: String, value: String, placeholder: String = "",
                error: String? = nil, multiline: Bool = false, onValueChange: (String) -> Void)
HealthTopBar(title: String, actionTitle: String? = nil, onAction: () -> Void = {},
             backTitle: String? = nil, onBack: () -> Void = {}, actionSymbol: String? = nil)
DoseRow(name: String, dose: String, meta: String, status: DoseStatusKind,
        timing: String? = nil, needsAction: Bool, canTake: Bool, canTakeAt: Bool,
        canSkip: Bool, canUndo: Bool, onTake: () -> Void, onTakeAt: () -> Void,
        onSkip: () -> Void, onUndo: () -> Void)
AsNeededRow(name: String, dose: String, summary: String, canUndo: Bool,
            onLog: () -> Void, onUndo: () -> Void)
InlineErrorBanner(message: String)
HistoryDayPager(dateLabel: String, canGoNext: Bool, onPrevious: () -> Void,
                onNext: () -> Void, onSelectDate: () -> Void)
ReminderRow(kind: String, fireAtLabel: String, title: String, bodyText: String)
HealthConfirmationDialog(isPresented: Bool, title: String, message: String,
                         confirmTitle: String, onConfirm: () -> Void, onDismiss: () -> Void)
// Apply with .modifier(...); Delete only. Host maps isConfirmingDelete.
```

### Android component API

All functions are `@Composable`; `modifier` defaults to `Modifier`. `DoseStatusKind`: Upcoming/Due/Taken/Skipped/Missed; `PillButtonKind`: Primary/Outline/Destructive. `HealthIcons` supplies vector Plus/Down/Back/Next/Check/Minus/Warning/Dot/Clock/Bell without another dependency.

```kotlin
HealthTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)
PillButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
           kind: PillButtonKind = PillButtonKind.Primary, enabled: Boolean = true,
           icon: ImageVector? = null)
HairlineDivider(modifier: Modifier = Modifier)
MetaRow(label: String, detail: String? = null, modifier: Modifier = Modifier)
ListRow(title: String, meta: String, detail: String? = null, modifier: Modifier = Modifier)
SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier)
StatusChip(kind: DoseStatusKind, modifier: Modifier = Modifier)
ProgressLine(fraction: Float, label: String, modifier: Modifier = Modifier)
EmptyStateView(message: String, modifier: Modifier = Modifier, title: String? = null,
               icon: ImageVector = HealthIcons.Plus, actionTitle: String? = null, onAction: () -> Unit = {})
FloatingPillButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier)
Banner(title: String, modifier: Modifier = Modifier, message: String? = null,
       actionTitle: String? = null, onAction: () -> Unit = {})
PillSelect(label: String, title: String, onClick: () -> Unit, modifier: Modifier = Modifier)
PillSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit,
                     modifier: Modifier = Modifier)
PillToggle(title: String, isOn: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
           enabled: Boolean = true, accessibilityTitle: String = title)
WeekdayPicker(selectedWeekdays: List<Int>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier)
HealthTextField(label: String, value: String, onValueChange: (String) -> Unit,
                modifier: Modifier = Modifier, placeholder: String = "", error: String? = null,
                singleLine: Boolean = true)
HealthTopBar(title: String, modifier: Modifier = Modifier, actionTitle: String? = null,
             onAction: () -> Unit = {}, backTitle: String? = null, onBack: () -> Unit = {},
             actionIcon: ImageVector? = null)
DoseRow(name: String, dose: String, meta: String, status: DoseStatusKind,
        needsAction: Boolean, canTake: Boolean, canTakeAt: Boolean, canSkip: Boolean, canUndo: Boolean,
        onTake: () -> Unit, onTakeAt: () -> Unit, onSkip: () -> Unit, onUndo: () -> Unit,
        modifier: Modifier = Modifier, timing: String? = null)
AsNeededRow(name: String, dose: String, summary: String, canUndo: Boolean,
            onLog: () -> Unit, onUndo: () -> Unit, modifier: Modifier = Modifier)
InlineErrorBanner(message: String, modifier: Modifier = Modifier)
HistoryDayPager(dateLabel: String, canGoNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit,
                onSelectDate: () -> Unit, modifier: Modifier = Modifier)
ReminderRow(kind: String, fireAtLabel: String, title: String, body: String, modifier: Modifier = Modifier)
HealthConfirmationDialog(isPresented: Boolean, title: String, message: String, confirmTitle: String,
                         onConfirm: () -> Unit, onDismiss: () -> Unit)
```

### App icon delivery

The existing iOS universal 1024 PNG and Android adaptive/monochrome heart assets remain the product identity. No external font or asset requests. iOS deployment target is 27; Android min/target SDK is 37. No signing identities, personal data, or machine-local toolchain files belong in this handoff.
