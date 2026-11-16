# Health Beta — mobile design system

Health Beta has three jobs: maintain a medication/supplement list, record taken or skipped doses, and show local reminders. The interface is a quiet daily ledger: large system type, an uninterrupted white or black canvas, small metadata, fine rules, and pill controls. There are no editorial articles, remote content, accounts, charts of medical outcomes, or extra health features.

## Visual principles

Use SF Pro through system APIs on iOS and the default system family (Roboto on standard Android) on Android. No bundled or proprietary fonts. A 390 × 844 logical-pixel phone is the review baseline; all production screens adapt to width, font size, keyboard, and safe areas. Desktop navigation, footers, language switches, and theme switches are omitted. Follow the device appearance automatically.

Keep content left aligned, on a single column with 20pt/dp side gutters. Prefer whitespace and dividers to cards. Reserve secondary panels for progress, empty states, or notification guidance. Use the supplied heart artwork only for the app icon and an optional 16pt/dp top-bar mark. The gradient never encodes dose state, a selected tab, or a primary button.

`mockups.html` is a self-contained static review sheet. Each screen appears in a light/dark pair, at 390 × 844. Scroll inside phones to inspect content below the fold. The editor also has a continuation pair so lower fields are immediately reviewable. Examples are illustrative labels, not treatment instructions.

## Semantic colors

These are resolved sRGB values on the primary canvas. The reference's alpha grays are flattened to avoid unexpected contrast over panels. Use roles, not literals, in features. Swift names are `HealthColors.*`; Compose names are `HealthTheme.colors.*`.

| Role (Swift / Kotlin) | Light | Dark | Use |
|---|---|---|---|
| background | #FFFFFF | #000000 | Canvas, safe areas, tab bar |
| secondaryBackground | #F2F2F2 | #141414 | Quiet panels |
| tertiaryBackground | #EBEBEB | #262626 | Progress track, grouped surfaces |
| inputBackground | #F2F2F2 | #121212 | 5% black / 7% white input surface |
| primary | #000000 | #FFFFFF | Main text; filled button surface; focus |
| onPrimary / background | #FFFFFF | #000000 | Filled button text |
| secondary | #666666 | #999999 | 60% foreground; metadata, hints |
| tertiary | #B3B3B3 | #4D4D4D | 30% foreground; disabled/decorative only |
| body | #474747 | #B8B8B8 | 72% foreground; explanatory text |
| border | #E6E6E6 | #1A1A1A | Decorative hairlines; reference 10% |
| strongBorder | #CCCCCC | #333333 | Pill outlines; reference 20% |
| buttonSecondary | #F2F2F2 | #1A1A1A | Secondary pressed/grouped surfaces |
| success | #007A51 | #53D6A3 | Taken text and symbol |
| successBackground | #DBF8EB | #002218 | Taken chip |
| destructive | #BF1725 | #FF7C86 | Missed status; destructive controls; validation |
| destructiveBackground | #FEDEE3 | #3D0105 | Missed chip; destructive pill |
| brandTop → brandBottom | #FF61AD → #FF2719 | Same | Heart only |
| toastBackground | #222222 | #222222 | Toast, with white text |

The reference's #00BA7C and #F4212E are shifted for small-text contrast against their tinted chip backgrounds. Red appears only for missed status, errors, or destructive actions. Due is monochrome, skipped is neutral, and notification guidance is neutral: no extra accent, warning yellow, link blue, or color-coded medication kinds. Focus uses the primary foreground. Outlines and surface contrast are decorative; controls also have visible labels, focus indication, and native accessibility semantics. Never use tertiary text for meaningful instructions or placeholder content. Toast opacity is raised from the reference's translucent treatment to ensure legibility.

## Type

Numbers below are default sizes before accessibility scaling. We map the reference onto native text categories rather than force identical metrics across systems. Default iOS body is 17pt; Android body is 16sp. All text wraps; do not ellipsize medication names in a dose/action context. In a dense overview, allow two lines and expose the complete label to accessibility.

| Token | Reference size / line | iOS (`HealthTypography`) | Android (`HealthType`) | Use |
|---|---|---|---|---|
| display | 48 / 52 medium | 48pt system medium, UIFontMetrics scaled | 48sp / 52sp medium | Optional empty-state hero only |
| title | 32 / 36 medium | largeTitle, 34pt medium | 32sp / 36sp medium | Screen title |
| section | Mobile adaptation | title2, 22pt medium | 22sp / 28sp medium | Sections / second line |
| row | 16 / 24 medium | body, 17pt medium | 16sp / 24sp medium | Medication name |
| body | 16 / 24 regular | body, 17pt regular | 16sp / 24sp regular | Explanatory copy |
| smallBody | 14 / 22.4 regular | subheadline, 15pt | 14sp / 22.4sp, +0.13sp tracking | Banner/body detail |
| meta | 13 / 20 medium | footnote, 13pt medium | 13sp / 20sp medium, +0.52sp tracking | Time, dose, labels, buttons |
| caption | 13 / 20 regular | footnote, 13pt | 13sp / 20sp | Secondary values |
| input | 15 / 22 regular | body, 17pt | 15sp / 22sp | Entered values |
| footnote | 10 / 10 | caption2, 11pt | 11sp / 14sp | Nonessential annotation only |

SwiftUI uses native text styles for Dynamic Type, with system line metrics. MetaRow applies 0.52pt tracking. Compose uses sp and system font scaling. SectionHeader's optional second line uses the same size and weight as its first line, in secondary color. Screen titles are 32/34, not the desktop 48 size.

## Geometry, hierarchy, and accessibility

- Grid: 4; spacing steps 4, 8, 12, 16, 24, 32, 48. Horizontal screen inset: 20. Section gaps: 24–32. Row padding: 16 vertically. Label/value gap: 8. Inline icon gap: 6–8.
- Controls: minimum 48pt/dp high (larger than the reference's 32–36). Native icon-only controls get at least 44pt on iOS / 48dp on Android, even if artwork is 16–24. Weekday pills each get 48 targets; wrap across lines at narrow widths.
- Radius: full pill, represented by Capsule/CircleShape, for short controls and chips; 16 for banners/panels; 24 for a dialog. Long multiline notes use a 16-radius rounded field. No shadows on content. Floating action gets a 6pt/dp canvas halo.
- Rules: one physical pixel (`1 / displayScale` / `Dp.Hairline`); full content width; primary foreground at the reference's 10% intensity. Control outlines: 1 logical point/dp. Focus outline: primary, 2 logical points/dp or the native keyboard focus treatment. Do not rely on the faint border as the only control cue.
- Interactive pills grow with their labels and font size. Wrap action rows when necessary; never shrink text or reduce targets. Form content scrolls above a keyboard-aware bottom Save area. Tab labels stay visible.
- Respect Dynamic Type / font scaling, VoiceOver / TalkBack, Reduce Motion, RTL layout, and locale date/time/decimal conventions. Dose status always includes text and a distinct symbol. Announce log results and validation politely once via the host. Use heading semantics, accessible field labels, native focus and dialog behavior. Restore focus to a triggering control when a dialog closes.
- The native components own no feature state, persistence, counters, timers, permission logic, or navigation. Hosts pass formatted strings, enabled/visible flags, selected indices, status presentation enums, progress fractions, and callbacks. Engineering defines the exact State/Action contracts and status/adherence rules.

## Components

### Pill buttons, select, and segmented control

Primary: primary fill, inverse label; outline: clear fill, primary label, strongBorder; destructive: destructiveBackground fill with destructive label. Label is medium meta; horizontal padding 20; minimum height 48. Pressed is visibly subdued; disabled is muted and noninteractive, accompanied by inline reasons where useful. Use primary for Save and Taken, outline for Skip, Edit, and notification settings; destructive only for Archive/Delete confirmation actions. Archive's entry control can be outline; the confirmation identifies its effect.

PillSelect has a label and trailing down chevron; opening a native time/date/unit chooser belongs to the host. PillSegmentedControl uses a secondary panel with 4 padding and 4 gaps, filled selected segment and outline unselected segments. It takes labels and a selected index; it does not own selection. Weekdays use a wrapping group of seven separately labeled pills with selected traits, not one index-based segmented control. Visual single-letter labels must have full weekday accessibility labels.

### Text field

Persistent label above, input surface, pill outline, native caret, meaningful placeholder in secondary text. Amount and unit align side by side when space permits; stack at large type. Keep entered values on error; add destructive border and a specific caption directly below the field. Never communicate invalidity with color alone. Notes use a native multiline text field in the same surface and a rounded rectangle. Hosts provide keyboard type, units, date/time pickers and error strings; `HealthTextField` is the basic text primitive, not a form model.

### MetaRow, ListRow, SectionHeader

MetaRow: 13 medium metadata, optional small decorative system symbol (iOS) and secondary detail separated by a dot. ListRow: top hairline, meta line, medication name, optional secondary detail; 16 vertical content padding. The host can wrap it in an accessible Button and append action/status content below; the row itself is presentational. Avoid nested buttons. SectionHeader: 22 medium, optional secondary second line at the same size, 4 gap. Date/time subgroups may instead use meta text to keep the screen quiet.

### Dose StatusChip

| Kind | Label and mark | Treatment |
|---|---|---|
| upcoming | Clock · Upcoming | Secondary text on secondary panel |
| due | Filled dot · Due | Inverse text on primary fill |
| taken | Check · Taken | Success text on successBackground |
| skipped | Minus · Skipped | Secondary text on secondary panel |
| missed | Exclamation · Missed | Destructive text on destructiveBackground |

Chips have 10 horizontal / 6 vertical padding and are noninteractive. Screen hosts decide whether any quick action is available for each dose. The component never infers status from timestamps. Use the chip alongside its medication and scheduled time, with the combined row accessible as one meaningful description.

### ProgressLine

Metadata such as “2 of 5 taken” above a 4-high monochrome track, filled to the supplied fraction. Keep text visible: a thin bar alone is insufficient. The host supplies fraction and wording, including the zero-scheduled case, skipped handling, and the excluded as-needed count. Use a secondary panel with 16 padding when a progress summary opens Today.

### EmptyStateView

Centered thin 28 symbol, 22 medium title, 14/15 body, optional primary pill, on secondary background with 32 padding. No large colorful illustration. Empty medication list: “Your list starts here” / “Add a medication or supplement to see your doses here.” / “Add medication”. No scheduled doses today: “Nothing scheduled today” with an explanatory line, preserving any as-needed section. All-done is a compact neutral success message, not an empty medication list and not a promise about health.

### FloatingPillButton, top bar, tabs

FloatingPillButton: primary pill with plus and “Add”, canvas halo 6, centered 12 above the bottom tab bar. The content scroll area reserves at least 80 extra bottom space so it cannot cover the last row. Never position it over a system home indicator. In large text, place it in normal flow above the tab bar if its label wraps.

Top bar: 48 minimum content height inside the safe area, horizontal gutters 20. Root: “Health Beta” meta wordmark and optional tiny heart; page title is a separate large line below. Detail/editor: labeled back/cancel control left and Edit/Save right if needed; title remains in content. No menus or appearance controls. Hosts can use native toolbar APIs with these tokens and need not use HealthTopBar.

Bottom bar: Today / Medications / History with calendar-day, capsule, and clock/list line icons and 11–13 labels. Selected icon and label are primary; others secondary; no colored Material indicator or iOS accent tint. Top hairline, background canvas, native safe-area inset. Each item has a minimum 48 target. Selection and back stack remain in Core. Keep the bar on Today/list/history/detail; editor is a full-screen form with Cancel and Save and no tabs. Platform back returns to the originating tab, preserving its scroll position as supplied by the host.

### Banner, confirmation dialog, toast

Notifications off: neutral 16-radius secondary panel, title “Notifications are off”, explanation “You can still log doses here.”, outline “Open settings” button. Above the first time group, below progress. This is guidance, not an error and never blocks logging. The host chooses the permission/settings action and whether the banner appears; no permission calls in the component.

Archive/Delete use native modal confirmation. Archive copy explains reminders stop and history remains. Delete copy explicitly explains the medication and its history will be removed; include the name. Cancel is always available and visually distinct from the destructive confirm. An accidental outside dismissal is cancellation. iOS uses a native confirmationDialog modifier; Android uses a neutral Material AlertDialog without tonal elevation. Mockups show the layout intent; native platform arrangement takes precedence.

Toast: short result such as “Dose marked taken”, white 14/15 text on opaque #222, pill padding 20 × 14, above the tab bar or keyboard. Host owns lifetime and accessibility announcement; do not put critical errors only in a timed toast. Avoid animation when Reduce Motion is enabled. A toast does not provide the only indication of saved state: the row also updates.

## Screen specifications

### A. Today

Top bar, large “Today”, secondary localized date. Progress panel with the supplied “2 of 5 taken” and ProgressLine. If reminders are disabled, show Banner next. Group scheduled doses by scheduled local time in chronological order; each group's time is a meta heading above hairline-separated medication rows. Every row has name, dose, kind or note if needed, and StatusChip. For an actionable dose, show Taken primary and Skip outline beneath the name, 8 gap; other status rows stay compact. Do not merge two medications into one log action. Keep missed entries visible rather than silently dropping them. All status and action availability comes from engineering.

Below scheduled groups, “As needed” SectionHeader and subtitle “Not part of today's schedule.” Each as-needed medication shows its dose and a “Log taken” outline pill, without an upcoming/due label or scheduled progress contribution. If the user opens it, the host supplies the log action/confirmation behavior; the design system never generates a schedule.

No medications: replace progress and time groups with EmptyStateView and Add medication; keep the top bar and tabs. No scheduled doses but existing medications: show “Nothing scheduled today”, then as-needed items if any. All scheduled doses taken: progress at its supplied complete value, compact “All scheduled doses taken” panel and completed rows; as-needed logging stays available. If all entries are resolved with some skipped, use “Today's scheduled doses are logged” instead of implying all were taken. No fireworks, streaks, or confetti.

### B. Medications

Top bar, large “Medications”, optional second line “Your daily list”. Active SectionHeader and count metadata, then ListRows: meta kind, name, secondary “dose · schedule summary”. Use a trailing chevron only when it helps indicate detail; the entire row opens the medication. Schedule summary examples: “Daily · 08:00”, “Mon, Wed, Fri · 20:00”, or “As needed”. Wrap long names and summaries. Below a 32 gap: Archived SectionHeader, muted explanatory caption, and archived rows in normal foreground contrast, without active status chips. Do not hide history. Floating Add pill above tabs; empty state offers the same primary action. Empty archive section can say “No archived medications”.

### C. Medication editor — Add / Edit

Full-screen scroll form, labeled Cancel/back top control, large “Add medication” or “Edit medication”, then these groups separated by 24 and/or hairlines:

1. Name labeled text field. Kind segmented Medication / Supplement.
2. Dose amount text field + unit PillSelect. Use locale decimal entry; don't infer a unit from kind.
3. As needed native toggle, with caption “No scheduled reminders”. When enabled, replace times/weekdays with “Log a dose whenever you take it.” Keep all visible values host-controlled; do not invent schedule values when switching modes.
4. For scheduled items: “Reminder times” SectionHeader; list of native time PillSelects, each paired with a labeled Remove control; outline “Add time”. Weekdays selector with seven independent pills and full accessibility labels. Add/remove and selection remain callbacks.
5. Start date PillSelect, then optional End date PillSelect with a clear “No end date” option. Dates stack vertically, never squeeze into unreadable columns.
6. Notes native multiline field, optional. A helper explains optionality; no extra medical advice.

Pinned Save primary pill above keyboard/safe area with sufficient scroll inset. Save is disabled until the host says valid; place specific inline error captions beside affected fields after the host reports an error. Example copy: “Enter a name”, “Enter a dose greater than zero”, “Choose at least one time”, “Choose at least one weekday”, “End date must follow start date”. These are proposed copy; engineering owns validation rules and exact literals. Do not define State fields here. Edit uses identical structure with populated values. If Cancel would discard edits, use a native confirmation with “Discard changes” and “Keep editing”; do not silently discard a dirty form.

### D. Medication detail

Back top bar and outline Edit action. Meta kind, large wrapping medication name, dose in secondary 22 text. “Schedule” SectionHeader, then rule-separated key/value rows for schedule summary, times, weekdays, date range, and notes when supplied. As-needed items say “As needed · no scheduled reminders”. “Adherence” section: prominent percentage and an explicit period/denominator caption supplied by engineering, e.g. “80% · 8 of 10 scheduled doses taken · last 7 days”. Render “—” and a reason when unavailable; do not calculate or prescribe an adherence algorithm in the view. Exclude as-needed from scheduled metrics only if the supplied product state says so.

“Recent history” SectionHeader and rows with localized date/time and StatusChip. A small list can continue vertically without a separate analytics destination. Empty history: “No doses logged yet.” At the bottom, outline Archive followed by destructive Delete, with 12 gap and native confirmations. Archived detail clearly labels “Archived” and retains history; hide inappropriate actions according to supplied presentation flags. Deleting is visually separated from routine edit/log actions by 32 and a divider.

### E. History

Root top bar, large “History”, optional secondary second line “Your dose log”. Newest day first, each day a SectionHeader with a localized date and supplied taken/total summary caption if available. Rows below are chronological within a day: time metadata, medication name and dose, and StatusChip. The row exposes its whole meaning to accessibility. Status is text as well as color; show skipped and missed as clearly as taken. As-needed entries are explicitly labeled “As needed” rather than implying a planned dose. No charts, filter dashboard, or remote export. Empty state: “No history yet” / “Doses you log will appear here.” Keep tabs visible.

## Implementation and integration

Files under the two DesignSystem/designsystem directories are presentational only and compile alongside the current app shells. They deliberately do not replace any existing screen. Import SwiftUI in iOS hosts; Android hosts import `app.reflex.mobile.designsystem.*` and wrap their content in `HealthTheme { }`. Apply the canvas explicitly to the host screen; a theme maps colors but does not draw a background or own system bars. Native system bars/insets should match the current canvas.

Use native platform pickers, switches, multiline notes inputs, toolbars and tab bars with these roles. The shared set provides the reusable visual primitives; it does not add navigation, date models, or local-notification behavior. The host may compose ListRow with a StatusChip and an action row; do not nest actions inside a single enclosing row Button.

### iOS component API

All are module-internal SwiftUI types, usable by any AppMobile view. Optional/default arguments are shown. `DoseStatusKind` has upcoming, due, taken, skipped, missed; `PillButtonKind` has primary, outline, destructive.

```swift
PillButtonStyle(kind: PillButtonKind = .primary) // .buttonStyle(...); use Button.disabled(...)
HairlineDivider()
MetaRow(label: String, detail: String? = nil, symbol: String? = nil)
ListRow(title: String, meta: String, detail: String? = nil)
SectionHeader(title: String, subtitle: String? = nil)
StatusChip(kind: DoseStatusKind)
ProgressLine(fraction: Double, label: String) // host supplies 0...1
EmptyStateView(title: String, message: String, symbol: String = "pills",
               actionTitle: String? = nil, onAction: () -> Void = {})
FloatingPillButton(title: String, symbol: String = "plus", onTap: () -> Void)
Banner(title: String, message: String, actionTitle: String, onAction: () -> Void)
PillSelect(title: String, onTap: () -> Void)
PillSegmentedControl(labels: [String], selectedIndex: Int, onSelect: (Int) -> Void)
HealthTextField(label: String, value: String, placeholder: String = "",
                error: String? = nil, onValueChange: (String) -> Void)
HealthTopBar(title: String, actionTitle: String? = nil, onAction: () -> Void = {},
             backTitle: String? = nil, onBack: () -> Void = {})
HealthToast(message: String)
HealthConfirmationDialog(isPresented: Bool, title: String, message: String,
                         confirmTitle: String, onConfirm: () -> Void, onDismiss: () -> Void)
// Apply with .modifier(HealthConfirmationDialog(...)). Host clears visibility on confirm/dismiss.
```

### Android component API

All functions below are `@Composable`. Modifier parameters default to `Modifier`; callbacks have no business dependencies. `DoseStatusKind` has Upcoming, Due, Taken, Skipped, Missed; `PillButtonKind` has Primary, Outline, Destructive.

```kotlin
HealthTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)
PillButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier,
           kind: PillButtonKind = PillButtonKind.Primary, enabled: Boolean = true)
HairlineDivider(modifier: Modifier = Modifier)
MetaRow(label: String, detail: String? = null, modifier: Modifier = Modifier)
ListRow(title: String, meta: String, detail: String? = null, modifier: Modifier = Modifier)
SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier)
StatusChip(kind: DoseStatusKind, modifier: Modifier = Modifier)
ProgressLine(fraction: Float, label: String, modifier: Modifier = Modifier)
EmptyStateView(title: String, message: String, modifier: Modifier = Modifier,
               glyph: String = "+", actionTitle: String? = null, onAction: () -> Unit = {})
FloatingPillButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier)
Banner(title: String, message: String, actionTitle: String, onAction: () -> Unit,
       modifier: Modifier = Modifier)
PillSelect(title: String, onClick: () -> Unit, modifier: Modifier = Modifier)
PillSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit,
                     modifier: Modifier = Modifier)
HealthTextField(label: String, value: String, onValueChange: (String) -> Unit,
                modifier: Modifier = Modifier, placeholder: String = "", error: String? = null,
                singleLine: Boolean = true)
HealthTopBar(title: String, modifier: Modifier = Modifier, actionTitle: String? = null,
             onAction: () -> Unit = {}, backTitle: String? = null, onBack: () -> Unit = {})
HealthToast(message: String, modifier: Modifier = Modifier)
HealthConfirmationDialog(isPresented: Boolean, title: String, message: String,
                         confirmTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit)
```

### App icon delivery

The iOS single universal 1024 PNG preserves the provided SVG composition and has an opaque white sRGB background. XcodeGen names AppIcon in the target's base settings. Android foreground reuses the exact heart path and both gradients, centered in a 108dp viewport at 60dp wide inside the 72dp inner square, on white. A solid heart provides themed monochrome masking on Android 13+; both standard and round adaptive resources include it. Android minSdk is 26, so legacy mipmaps are omitted. Runtime assets make no external requests. The scratch rasterizer and source reference files remain outside the repository.
