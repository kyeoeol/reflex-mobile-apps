# Health Beta: scenarios

Health Beta does one thing: keep a list of medications and supplements, log the doses, and remind the user at
dose times with local notifications. No account, no server, no network.

This list is the refined result of three research tracks (reminder delivery and OS constraints, logging and data
model, a survey of existing apps such as Apple Health Medications, Medisafe, MyTherapy, Round Health, CareClinic
and Samsung Health). Every scenario marked **MVP** is specified precisely in [CONTRACT.md](CONTRACT.md) and covered
by a behavior test in `tests/behavior/health-*.cli-test.ts` (one test file per journey area). "Later" items are
deliberately out of the first version.

## Medications (adding, editing, lifecycle)

| # | Scenario | Priority |
|---|---|---|
| M1 | Add a medication with only a name and one time; everything else is optional (fewest steps) | MVP |
| M2 | Several times a day; specific weekdays; start and end date (courses such as antibiotics) | MVP |
| M3 | As-needed medication: no times, no reminders, log any time | MVP |
| M4 | Validation with literal messages: empty name, duplicate active name, no time, duplicate time, invalid time, no weekday, end before start; at most 12 times a day | MVP |
| M5 | Editing a schedule never rewrites history: the new schedule applies from today, past days keep the old times | MVP |
| M6 | Archive (stops doses and reminders, keeps history) and restore (doses resume from today) | MVP |
| M7 | Delete with confirmation removes the medication, its history and its reminders | MVP |
| M8 | A course whose end date has passed moves to "Ended" and stops producing doses | MVP |
| M9 | Every N days, cycles (21 on / 7 off), tapering, refill tracking, drug database, label scan | Later |

## Logging

| # | Scenario | Priority |
|---|---|---|
| L1 | Take a dose now; skip a dose; undo either (status returns to the computed one) | MVP |
| L2 | Taking an already-taken dose is a no-op (double tap, app + notification): one log per dose | MVP |
| L3 | Record the actual time a dose was taken (also to correct it); never in the future, at most 12 h from the scheduled time | MVP |
| L4 | Early / late flag (more than 60 min before / after the scheduled time); display only | MVP |
| L5 | Missed is computed, never stored: an unlogged dose is missed 120 min after its time, and can still be logged | MVP |
| L6 | Log a missed dose of a past day from History | MVP |
| L7 | As-needed: several logs a day, count and last time, undo the latest | MVP |
| L8 | A late-night dose (23:30) is still shown, and loggable, after midnight until its missed time | MVP |
| L9 | Logs keep the medication name and dose they were taken with | MVP |
| L10 | Partial doses, dose amounts per log, reasons for skipping, notes per log | Later |

## Today, History

| # | Scenario | Priority |
|---|---|---|
| T1 | Today groups doses by time; statuses upcoming / due / taken / skipped / missed; "2 of 5 taken" | MVP |
| T2 | Empty state ("No medications yet"), "Nothing scheduled today", "All done for today" | MVP |
| T3 | As-needed section with today's count and last time | MVP |
| T4 | History per day (browse back, pick a date), with the same dose actions | MVP |
| T5 | Medication detail with 30-day adherence (taken / (taken + skipped + missed), upcoming and due excluded) and its history | MVP |
| T6 | Stored data that can't be read shows an error and is never overwritten | MVP |
| T7 | Charts, streaks, export | Later |

## Reminders

| # | Scenario | Priority |
|---|---|---|
| R1 | One notification per time slot (all medications due at the same minute together), listing only unlogged doses | MVP |
| R2 | One follow-up 30 min after the reminder if the slot is still unlogged | MVP |
| R3 | Logging early (or skipping) cancels that dose's reminder and follow-up; undo re-arms them | MVP |
| R4 | Notification actions: Taken / Skip log the whole slot without opening the app; tapping opens Today | MVP |
| R5 | Snooze: 10 min, replaces the follow-up, never past the missed time | MVP |
| R6 | Editing, archiving, deleting or ending a medication removes its reminders (no ghost notifications) | MVP |
| R7 | Reminders are computed by Core as a full desired set and reconciled by a thin platform scheduler (diff by stable id) | MVP |
| R8 | Rolling window: next 7 days, at most 60 pending; when truncated, the last one asks the user to open the app (iOS 64-pending limit) | MVP |
| R9 | Time zone change: reminders follow local wall-clock time; history keeps the offset it was logged in | MVP |
| R10 | DST: a time that doesn't exist fires once, shifted by the gap (02:30 → 03:30); a repeated time fires once, at its first occurrence | MVP |
| R11 | Permission: asked when the first scheduled medication is saved (not at launch); denied → "Reminders are off" banner, everything else still works | MVP |
| R12 | Android exact alarms unavailable → "Reminders may arrive a few minutes late" (inexact window) | MVP |
| R13 | Reliability: reschedule on launch, foreground, every change, clock / time zone change; Android also on boot, time set, time zone change, exact-alarm grant | MVP (platform code) |
| R14 | Lock-screen privacy option, time-sensitive interruption level, user-chosen snooze length, "keep home time zone" per medication | Later |
| R15 | Critical alerts (needs an Apple entitlement), caregiver alerts, sync, accounts | Out of scope |

## Privacy (public repo, no server)

- Zero network calls. Data stays on the device. Android backup is disabled (`allowBackup=false`); the iOS data file
  is excluded from backups. Reinstalling removes the data.
- Notifications show medication names; a lock-screen privacy option is "Later".
