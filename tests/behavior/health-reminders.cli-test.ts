// Health Beta: reminders, notification responses, permissions, time zones and DST
// (docs/health/CONTRACT.md §1 Reminders, §2, §4). Notification responses arrive as device events.
//
// Fixtures put "now" at Monday 2026-11-16 09:00 in Asia/Seoul unless a test says otherwise.

import { createBehaviorSession, expect, fixture, test } from "../sdk";
import { openReminders, openToday, reminderIds, respondToReminder, selectTab, todayDose } from "./health";

const REFRESH = { id: "refresh", kind: "refresh", title: "Open Health Beta", body: "Open the app to keep your reminders coming." };

test("given scheduled medications, the user sees a week of reminders, one per time slot", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  const reminders = await openReminders(session, ({ state }) => {
    expect(state).toMatchObject({ permission: "authorized", mayBeLate: false, statusLabel: "Reminders are on", actionLabel: null });
    expect(state.reminders).toHaveLength(35);
  });
  expect(reminders.state.reminders.slice(0, 5)).toEqual([
    { id: "dose:2026-11-16T13:00", kind: "dose", fireAt: "2026-11-16T13:00:00+09:00", title: "Time for Omega-3", body: "Omega-3 (2 capsules)" },
    { id: "followup:2026-11-16T13:00", kind: "followUp", fireAt: "2026-11-16T13:30:00+09:00", title: "Still due: Omega-3", body: "Omega-3 (2 capsules)" },
    { id: "dose:2026-11-16T20:00", kind: "dose", fireAt: "2026-11-16T20:00:00+09:00", title: "Time for Metformin", body: "Metformin (500 mg)" },
    { id: "followup:2026-11-16T20:00", kind: "followUp", fireAt: "2026-11-16T20:30:00+09:00", title: "Still due: Metformin", body: "Metformin (500 mg)" },
    // Two medications at the same minute share one notification.
    {
      id: "dose:2026-11-17T08:00", kind: "dose", fireAt: "2026-11-17T08:00:00+09:00",
      title: "Time for 2 medications", body: "Metformin (500 mg), Vitamin D (1 capsule)",
    },
  ]);
  // The window ends 7 days from now with a reminder to open the app, so reminders never just stop.
  expect(reminderIds(reminders.state).slice(-2)).toEqual(["followup:2026-11-23T08:00", "refresh"]);
  expect(reminders.state.reminders.at(-1)).toEqual({ ...REFRESH, fireAt: "2026-11-23T09:00:00+09:00" });
  // Archived, ended and as-needed medications never remind.
  expect(reminders.state.reminders.some((r: any) => /Zinc|Amoxicillin|Ibuprofen/.test(r.body))).toBe(false);
  expect(reminders.actions.map((a) => a.title)).toEqual(["TurnOnReminders", "goBack", "selectTab"]);
});

test("given a dose logged early, its reminder and follow-up are not sent until the log is undone", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  await session.do({
    action: "Take",
    payload: { doseId: "omega@2026-11-16T13:00" },
    expectation: ({ state }) => expect(todayDose(state, "omega@2026-11-16T13:00")).toMatchObject({ status: "taken", timing: "early" }),
  });
  await openReminders(session, ({ state }) => {
    expect(reminderIds(state)[0]).toBe("dose:2026-11-16T20:00");
    expect(state.reminders).toHaveLength(33);
  });
  await session.do({ action: "goBack", expectation: ({ name }) => expect(name).toBe("Today") });
  await session.do({
    action: "Undo",
    payload: { doseId: "omega@2026-11-16T13:00" },
    expectation: ({ state }) => expect(todayDose(state, "omega@2026-11-16T13:00").status).toBe("upcoming"),
  });
  await openReminders(session, ({ state }) => {
    expect(reminderIds(state).slice(0, 2)).toEqual(["dose:2026-11-16T13:00", "followup:2026-11-16T13:00"]);
    expect(state.reminders).toHaveLength(35);
  });
});

test("given the user taps Taken on a reminder, every unlogged dose of that time is logged", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  // The app is open on another tab when the response arrives.
  await selectTab(session, "medications", "Medications");
  await respondToReminder(session, "2026-11-16T08:00", "taken");
  const today = await openToday(session, ({ state }) => {
    expect(todayDose(state, "vitd@2026-11-16T08:00")).toMatchObject({ status: "taken", takenAt: "09:00" });
    // Already taken in the app: untouched.
    expect(todayDose(state, "metformin@2026-11-16T08:00")).toMatchObject({ status: "taken", takenAt: "07:50" });
    expect(state.progressLabel).toBe("2 of 4 taken");
  });
  expect(today.path).toEqual(["App", "TabNavigator", "StackNavigator", "Today"]);
  // A second Taken (e.g. from the follow-up) changes nothing.
  await respondToReminder(session, "2026-11-16T08:00", "taken");
  await openToday(session, ({ state }) => expect(state.takenCount).toBe(2));
});

test("given the user taps Skip on a reminder, the doses of that time are skipped", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await respondToReminder(session, "2026-11-16T08:00", "skipped");
  await openToday(session, ({ state }) => {
    expect(todayDose(state, "vitd@2026-11-16T08:00")).toMatchObject({ status: "skipped", canUndo: true });
    expect(todayDose(state, "metformin@2026-11-16T08:00").status).toBe("taken");
  });
});

test("given the user snoozes a reminder, it comes back in 10 minutes for the doses still due, once", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await respondToReminder(session, "2026-11-16T08:00", "snooze");
  await openToday(session, ({ state }) => expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("due"));
  await openReminders(session, ({ state }) => {
    expect(state.reminders[0]).toEqual({
      id: "snooze:2026-11-16T08:00", kind: "snooze", fireAt: "2026-11-16T09:10:00+09:00",
      title: "Time for Vitamin D", body: "Vitamin D (1 capsule)",
    });
    expect(state.reminders).toHaveLength(36);
  });
  // After it has fired, it's gone (the open Reminders screen updates).
  await session.device({ now: "2026-11-16T09:11:00" });
  await session.precondition("Reminders", ({ state }) => {
    expect(reminderIds(state)[0]).toBe("dose:2026-11-16T13:00");
    expect(reminderIds(state).some((id) => id.startsWith("snooze:"))).toBe(false);
  });
});

test("given a dose about to be missed, snoozing doesn't push its reminder past the missed time", async () => {
  // Now 09:55: ten more minutes would be after the 08:00 dose turns missed at 10:00.
  await using session = createBehaviorSession({ fixtures: [fixture("Health.almostMissed")] });
  await respondToReminder(session, "2026-11-16T08:00", "snooze");
  await openToday(session, ({ state }) => expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("due"));
  await openReminders(session, ({ state }) => {
    expect(reminderIds(state)[0]).toBe("dose:2026-11-16T13:00");
    expect(reminderIds(state).some((id) => id.startsWith("snooze:"))).toBe(false);
  });
});

test("given the user taps a reminder, the app opens on Today without logging anything", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  await selectTab(session, "history", "History");
  await respondToReminder(session, "2026-11-16T13:00", "open");
  await openToday(session, ({ state }) => {
    expect(todayDose(state, "omega@2026-11-16T13:00").status).toBe("upcoming");
    expect(state.takenCount).toBe(1);
  });
});

test("given a link that looks like a reminder action, opening it never logs a dose", async () => {
  // Any app can open a URL; only real notification responses log doses.
  await using session = createBehaviorSession({
    fixtures: [fixture("Health.default")],
    deeplink: "healthbeta://reminder/2026-11-16T08:00/taken",
  });
  await openToday(session, ({ state }) => expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("due"));
  await session.event("openURL", { url: "healthbeta://reminder/2026-11-16T08:00/skipped" });
  await openToday(session, ({ state }) => expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("due"));
});

test("given notifications are denied, the user is told reminders are off, and turning them on in Settings schedules them", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.permissionDenied")] });
  await openToday(session, ({ state }) => {
    expect(state).toMatchObject({ reminderBanner: "Reminders are off", reminderBannerAction: "Open Settings" });
  });
  await openReminders(session, ({ state }) => {
    expect(state).toMatchObject({
      permission: "denied", statusLabel: "Reminders are off. Turn them on in Settings.", actionLabel: "Open Settings", reminders: [],
    });
  });
  // Opens the system settings; nothing changes in the app until the user comes back with permission.
  await session.do({ action: "TurnOnReminders", expectation: ({ state }) => expect(state.permission).toBe("denied") });
  // The user comes back from Settings with reminders allowed; the open Reminders screen updates.
  await session.device({ notificationPermission: "authorized" });
  await session.precondition("Reminders", ({ state }) => {
    expect(state).toMatchObject({ permission: "authorized", statusLabel: "Reminders are on" });
    expect(state.reminders).toHaveLength(35);
  });
  await session.do({
    action: "goBack",
    expectation: ({ name, state }) => {
      expect(name).toBe("Today");
      expect(state.reminderBanner).toBeNull();
    },
  });
  // Logging works either way.
  await session.do({
    action: "Take",
    payload: { doseId: "vitd@2026-11-16T08:00" },
    expectation: ({ state }) => expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("taken"),
  });
});

test("given reminders were never set up, the user can turn them on and they get scheduled", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.permissionNotDetermined")] });
  await openToday(session, ({ state }) => {
    expect(state).toMatchObject({ reminderBanner: "Turn on reminders to get notified", reminderBannerAction: "Turn on reminders" });
  });
  await openReminders(session, ({ state }) => {
    expect(state).toMatchObject({
      permission: "notDetermined", statusLabel: "Reminders aren't set up yet", actionLabel: "Turn on reminders", reminders: [],
    });
  });
  await session.do({
    action: "TurnOnReminders",
    expectation: ({ state }) => {
      expect(state).toMatchObject({ permission: "authorized", statusLabel: "Reminders are on", actionLabel: null });
      expect(state.reminders).toHaveLength(35);
    },
  });
  await session.do({
    action: "goBack",
    expectation: ({ name, state }) => {
      expect(name).toBe("Today");
      expect(state.reminderBanner).toBeNull();
    },
  });
});

test("given the user declines notifications when saving a medication, Today says reminders are off", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.declinesNotifications")] });
  await openToday(session);
  await session.do({ action: "AddMedication", expectation: ({ name }) => expect(name).toBe("MedicationEditor") });
  await session.do({ action: "SetName", payload: { name: "Vitamin D" }, expectation: ({ state }) => expect(state.name).toBe("Vitamin D") });
  await session.do({ action: "AddTime", payload: { time: "20:00" }, expectation: ({ state }) => expect(state.isValid).toBe(true) });
  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("Today");
      expect(state).toMatchObject({ reminderBanner: "Reminders are off", reminderBannerAction: "Open Settings" });
      expect(state.groups.map((g: any) => g.key)).toEqual(["2026-11-16T20:00"]);
    },
  });
  await openReminders(session, ({ state }) => expect(state).toMatchObject({ permission: "denied", reminders: [] }));
});

test("given exact alarms aren't allowed, the user is told reminders may arrive a few minutes late", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.remindersMayBeLate")] });
  await openToday(session, ({ state }) => {
    expect(state).toMatchObject({ reminderBanner: "Reminders may arrive a few minutes late", reminderBannerAction: "Open Settings" });
  });
  await openReminders(session, ({ state }) => {
    expect(state).toMatchObject({
      permission: "authorized", mayBeLate: true, statusLabel: "Reminders may arrive a few minutes late", actionLabel: "Open Settings",
    });
    expect(state.reminders).toHaveLength(35);
  });
});

test("given the user travels to another time zone, reminders follow local time and history keeps when doses were taken", async () => {
  // Now: 2026-11-16 07:00 in Los Angeles; yesterday's Vitamin D was logged at 08:10 in Seoul.
  await using session = createBehaviorSession({ fixtures: [fixture("Health.travel")] });
  await openToday(session, ({ state }) => {
    expect(state.date).toBe("2026-11-16");
    expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("upcoming");
  });
  await openReminders(session, ({ state }) => {
    expect(state.reminders[0]).toMatchObject({ id: "dose:2026-11-16T08:00", fireAt: "2026-11-16T08:00:00-08:00" });
  });
  await selectTab(session, "history", "History");
  await session.do({
    action: "PreviousDay",
    expectation: ({ state }) => expect(state.doses[0]).toMatchObject({ id: "vitd@2026-11-15T08:00", status: "taken", takenAt: "08:10" }),
  });
});

test("given the time zone changes while the app is open, reminders move to the new local time", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  await session.device({ timeZone: "America/Los_Angeles", now: "2026-11-16T07:00:00" });
  await openReminders(session, ({ state }) => {
    expect(state.reminders[0]).toMatchObject({
      id: "dose:2026-11-16T08:00", fireAt: "2026-11-16T08:00:00-08:00", title: "Time for Vitamin D",
    });
  });
});

test("given a dose time that doesn't exist on a daylight-saving day, its reminder fires once right after the gap", async () => {
  // New York, 2027-03-14: clocks jump from 02:00 to 03:00. The Night pill is due at 02:30.
  await using session = createBehaviorSession({ fixtures: [fixture("Health.dstSpringForward")] });
  await openToday(session, ({ state }) => {
    expect(state.groups).toHaveLength(1);
    expect(todayDose(state, "night@2027-03-14T02:30")).toMatchObject({ time: "02:30", status: "upcoming", dose: "" });
  });
  await openReminders(session, ({ state }) => {
    expect(state.reminders.slice(0, 3)).toEqual([
      { id: "dose:2027-03-14T02:30", kind: "dose", fireAt: "2027-03-14T03:30:00-04:00", title: "Time for Night pill", body: "Night pill" },
      { id: "followup:2027-03-14T02:30", kind: "followUp", fireAt: "2027-03-14T04:00:00-04:00", title: "Still due: Night pill", body: "Night pill" },
      { id: "dose:2027-03-15T02:30", kind: "dose", fireAt: "2027-03-15T02:30:00-04:00", title: "Time for Night pill", body: "Night pill" },
    ]);
  });
});

test("given a dose time that happens twice on a daylight-saving day, its reminder fires only once", async () => {
  // New York, 2026-11-01: 01:00-02:00 happens twice. The Night pill is due at 01:30.
  await using session = createBehaviorSession({ fixtures: [fixture("Health.dstFallBack")] });
  await openToday(session);
  await openReminders(session, ({ state }) => {
    expect(state.reminders.slice(0, 3).map((r: any) => [r.id, r.fireAt])).toEqual([
      ["dose:2026-11-01T01:30", "2026-11-01T01:30:00-04:00"],
      // 30 minutes after the first 01:30 is the second 01:00.
      ["followup:2026-11-01T01:30", "2026-11-01T01:00:00-05:00"],
      ["dose:2026-11-02T01:30", "2026-11-02T01:30:00-05:00"],
    ]);
    expect(state.reminders).toHaveLength(15);
    // Seven days after midnight EDT is 23:00 EST.
    expect(state.reminders.at(-1)).toEqual({ ...REFRESH, fireAt: "2026-11-07T23:00:00-05:00" });
  });
});

test("given more doses than the system can hold, the reminder list is capped and ends by asking to open the app", async () => {
  // Twelve times a day: far more than 60 pending notifications in a week.
  await using session = createBehaviorSession({ fixtures: [fixture("Health.manyDoses")] });
  await openToday(session);
  await openReminders(session, ({ state }) => {
    expect(state.reminders).toHaveLength(60);
    expect(reminderIds(state)[0]).toBe("followup:2026-11-16T09:00");
    expect(state.reminders[58]).toMatchObject({ id: "followup:2026-11-19T02:00", fireAt: "2026-11-19T02:30:00+09:00" });
    expect(state.reminders[59]).toEqual({ ...REFRESH, fireAt: "2026-11-19T02:31:00+09:00" });
  });
});
