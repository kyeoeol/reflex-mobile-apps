// Health Beta: the Today screen (docs/health/CONTRACT.md §3 Today). One file, both platforms.
//
// Fixtures put "now" at Monday 2026-11-16 09:00 in Asia/Seoul unless a test says otherwise.

import { createBehaviorSession, expect, fixture, test } from "../sdk";
import { openToday, selectTab, todayDose, todayDoses } from "./health";

test("given a fresh install, the user sees an empty Today and can start adding a medication", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.empty")] });
  const today = await openToday(session, ({ state }) => {
    expect(state).toEqual({
      isLoading: false,
      date: "2026-11-16",
      progressLabel: null,
      takenCount: 0,
      scheduledCount: 0,
      message: "No medications yet",
      reminderBanner: null,
      groups: [],
      asNeeded: [],
      doseError: null,
    });
  });
  expect(today.path).toEqual(["App", "TabNavigator", "StackNavigator", "Today"]);
  expect(today.actions.map((a) => a.title)).toEqual([
    "Take", "TakeAt", "Skip", "Undo", "LogAsNeeded", "UndoAsNeeded", "TurnOnReminders", "OpenReminders",
    "AddMedication", "OpenMedication", "goBack", "selectTab",
  ]);

  await session.do({
    action: "AddMedication",
    expectation: ({ name, state }) => {
      expect(name).toBe("MedicationEditor");
      expect(state).toMatchObject({
        mode: "add", medicationId: null, name: "", kind: "medication", dose: "", notes: "", asNeeded: false,
        times: [], weekdays: [1, 2, 3, 4, 5, 6, 7], startDate: "2026-11-16", endDate: null, canSave: false,
      });
    },
  });
  await session.do({ action: "goBack", expectation: ({ name }) => expect(name).toBe("Today") });
});

test("given scheduled doses, the user sees today's doses grouped by time with their progress", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session, ({ state }) => {
    expect(state.date).toBe("2026-11-16");
    expect(state.progressLabel).toBe("1 of 4 taken");
    expect(state.takenCount).toBe(1);
    expect(state.scheduledCount).toBe(4);
    expect(state.message).toBeNull();
    expect(state.reminderBanner).toBeNull();
    // Archived (Zinc) and ended (Amoxicillin) medications never show up; yesterday's missed 20:00 dose is gone.
    expect(state.groups.map((g: any) => g.key)).toEqual(["2026-11-16T08:00", "2026-11-16T13:00", "2026-11-16T20:00"]);
    expect(state.groups[0]).toEqual({
      key: "2026-11-16T08:00",
      date: "2026-11-16",
      time: "08:00",
      doses: [
        {
          id: "metformin@2026-11-16T08:00", medicationId: "metformin", name: "Metformin", dose: "500 mg",
          date: "2026-11-16", time: "08:00", status: "taken", takenAt: "07:50", timing: "onTime",
          canTake: false, canSkip: true, canUndo: true,
        },
        {
          id: "vitd@2026-11-16T08:00", medicationId: "vitd", name: "Vitamin D", dose: "1 capsule",
          date: "2026-11-16", time: "08:00", status: "due", takenAt: null, timing: null,
          canTake: true, canSkip: true, canUndo: false,
        },
      ],
    });
    expect(todayDose(state, "omega@2026-11-16T13:00")).toMatchObject({ name: "Omega-3", status: "upcoming" });
    expect(todayDose(state, "metformin@2026-11-16T20:00")).toMatchObject({ status: "upcoming", canUndo: false });
    expect(state.asNeeded).toEqual([
      {
        medicationId: "ibuprofen", name: "Ibuprofen", dose: "200 mg", takenToday: 1, lastTakenAt: "08:30",
        summary: "Taken once today, last at 08:30",
      },
    ]);
  });
});

test("given a due dose, the user can take it, undo it and skip it instead", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  const id = "vitd@2026-11-16T08:00";

  await session.do({
    action: "Take",
    payload: { doseId: id },
    expectation: ({ state }) => {
      // Taken exactly 60 minutes after its time still counts as on time.
      expect(todayDose(state, id)).toMatchObject({ status: "taken", takenAt: "09:00", timing: "onTime", canTake: false, canUndo: true });
      expect(state.progressLabel).toBe("2 of 4 taken");
    },
  });
  // A second tap (or the notification after the app) never logs twice.
  await session.do({
    action: "Take",
    payload: { doseId: id },
    expectation: ({ state }) => {
      expect(todayDose(state, id)).toMatchObject({ status: "taken", takenAt: "09:00" });
      expect(state.takenCount).toBe(2);
      expect(state.doseError).toBeNull();
    },
  });
  await session.do({
    action: "Undo",
    payload: { doseId: id },
    expectation: ({ state }) => {
      expect(todayDose(state, id)).toMatchObject({ status: "due", takenAt: null, timing: null, canUndo: false });
      expect(state.progressLabel).toBe("1 of 4 taken");
    },
  });
  await session.do({
    action: "Skip",
    payload: { doseId: id },
    expectation: ({ state }) => {
      expect(todayDose(state, id)).toMatchObject({ status: "skipped", takenAt: null, canTake: true, canSkip: false, canUndo: true });
      // Skipped doses don't count as taken.
      expect(state.progressLabel).toBe("1 of 4 taken");
    },
  });
  // Changing your mind: skipped → taken directly.
  await session.do({
    action: "Take",
    payload: { doseId: id },
    expectation: ({ state }) => expect(todayDose(state, id)).toMatchObject({ status: "taken", canSkip: true }),
  });
});

test("given a dose taken at another time, the user can record when it was actually taken", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  const vitd = "vitd@2026-11-16T08:00";

  await session.do({
    action: "TakeAt",
    payload: { doseId: vitd, takenAt: "2026-11-16T07:30" },
    expectation: ({ state }) => expect(todayDose(state, vitd)).toMatchObject({ status: "taken", takenAt: "07:30", timing: "onTime" }),
  });
  // Correcting the time of an existing log; 90 minutes before the dose is early.
  await session.do({
    action: "TakeAt",
    payload: { doseId: vitd, takenAt: "2026-11-16T06:30" },
    expectation: ({ state }) => {
      expect(todayDose(state, vitd)).toMatchObject({ takenAt: "06:30", timing: "early" });
      expect(state.takenCount).toBe(2);
    },
  });
  await session.do({
    action: "TakeAt",
    payload: { doseId: vitd, takenAt: "2026-11-16T10:00" },
    expectation: ({ state }) => {
      expect(state.doseError).toBe("Time can't be in the future");
      expect(todayDose(state, vitd)).toMatchObject({ takenAt: "06:30" });
    },
  });
  await session.do({
    action: "TakeAt",
    payload: { doseId: vitd, takenAt: "tomorrow" },
    expectation: ({ state }) => expect(state.doseError).toBe("Enter a time as yyyy-MM-ddTHH:mm"),
  });
  // 13 hours before the 20:00 dose is too far from it, even with Take (now is 09:00, 11 h before: fine).
  await session.do({
    action: "TakeAt",
    payload: { doseId: "metformin@2026-11-16T20:00", takenAt: "2026-11-16T07:00" },
    expectation: ({ state }) => {
      expect(state.doseError).toBe("Time must be within 12 hours of the scheduled dose");
      expect(todayDose(state, "metformin@2026-11-16T20:00")).toMatchObject({ status: "upcoming" });
    },
  });
  await session.do({
    action: "Take",
    payload: { doseId: "metformin@2026-11-16T20:00" },
    expectation: ({ state }) => {
      expect(state.doseError).toBeNull();
      expect(todayDose(state, "metformin@2026-11-16T20:00")).toMatchObject({ status: "taken", takenAt: "09:00", timing: "early" });
    },
  });
});

test("given every dose of the day is logged, the user sees that they are all done", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  await session.do({ action: "Take", payload: { doseId: "vitd@2026-11-16T08:00" }, expectation: ({ state }) => expect(state.takenCount).toBe(2) });
  await session.do({
    action: "Skip",
    payload: { doseId: "omega@2026-11-16T13:00" },
    expectation: ({ state }) => {
      expect(todayDose(state, "omega@2026-11-16T13:00").status).toBe("skipped");
      expect(state.message).toBeNull();
    },
  });
  await session.do({
    action: "Take",
    payload: { doseId: "metformin@2026-11-16T20:00" },
    expectation: ({ state }) => {
      expect(state.message).toBe("All done for today");
      expect(state.progressLabel).toBe("3 of 4 taken");
      expect(todayDoses(state).every((d) => d.canUndo)).toBe(true);
    },
  });
});

test("given an as-needed medication, the user can log doses whenever they take one", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.asNeededOnly")] });
  await openToday(session, ({ state }) => {
    expect(state.message).toBe("Nothing scheduled today");
    expect(state.progressLabel).toBeNull();
    expect(state.groups).toEqual([]);
    // No scheduled medication: nothing to remind about, so no banner.
    expect(state.reminderBanner).toBeNull();
    expect(state.asNeeded).toEqual([
      { medicationId: "ibuprofen", name: "Ibuprofen", dose: "200 mg", takenToday: 0, lastTakenAt: null, summary: "Not taken today" },
    ]);
  });
  await session.do({
    action: "LogAsNeeded",
    payload: { medicationId: "ibuprofen" },
    expectation: ({ state }) => expect(state.asNeeded[0]).toMatchObject({ takenToday: 1, lastTakenAt: "09:00", summary: "Taken once today, last at 09:00" }),
  });
  await session.do({
    action: "LogAsNeeded",
    payload: { medicationId: "ibuprofen" },
    expectation: ({ state }) => expect(state.asNeeded[0]).toMatchObject({ takenToday: 2, summary: "Taken 2 times today, last at 09:00" }),
  });
  await session.do({
    action: "UndoAsNeeded",
    payload: { medicationId: "ibuprofen" },
    expectation: ({ state }) => expect(state.asNeeded[0].takenToday).toBe(1),
  });
  await session.do({
    action: "UndoAsNeeded",
    payload: { medicationId: "ibuprofen" },
    expectation: ({ state }) => expect(state.asNeeded[0]).toMatchObject({ takenToday: 0, summary: "Not taken today" }),
  });
  // Nothing left to undo, and unknown medications are ignored.
  await session.do({ action: "UndoAsNeeded", payload: { medicationId: "ibuprofen" }, expectation: ({ state }) => expect(state.asNeeded[0].takenToday).toBe(0) });
  await session.do({ action: "LogAsNeeded", payload: { medicationId: "nope" }, expectation: ({ state }) => expect(state.asNeeded).toHaveLength(1) });
});

test("given a late-night dose, after midnight the user still sees it under the previous day and can take it", async () => {
  // Now: Tuesday 2026-11-17 00:15; Melatonin is due every day at 23:30.
  await using session = createBehaviorSession({ fixtures: [fixture("Health.lateNight")] });
  await openToday(session, ({ state }) => {
    expect(state.date).toBe("2026-11-17");
    expect(state.groups.map((g: any) => [g.date, g.time])).toEqual([["2026-11-16", "23:30"], ["2026-11-17", "23:30"]]);
    expect(todayDose(state, "melatonin@2026-11-16T23:30").status).toBe("due");
    // Progress only counts today's own doses.
    expect(state.progressLabel).toBe("0 of 1 taken");
  });
  await session.do({
    action: "Take",
    payload: { doseId: "melatonin@2026-11-16T23:30" },
    expectation: ({ state }) => {
      expect(todayDose(state, "melatonin@2026-11-16T23:30")).toMatchObject({ status: "taken", takenAt: "00:15", timing: "onTime" });
      expect(state.progressLabel).toBe("0 of 1 taken");
    },
  });
});

test("given stored data that can't be read, the user sees an error instead of an empty app", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.unreadableData")] });
  await openToday(session, ({ state }) => {
    expect(state.message).toBe("Couldn't load your data");
    expect(state.groups).toEqual([]);
    expect(state.reminderBanner).toBeNull();
  });
  // Nothing can be written over the unreadable data.
  await selectTab(session, "medications", "Medications");
  await session.do({ action: "Add", expectation: ({ name }) => expect(name).toBe("MedicationEditor") });
  await session.do({ action: "SetName", payload: { name: "Vitamin D" }, expectation: ({ state }) => expect(state.name).toBe("Vitamin D") });
  await session.do({ action: "AddTime", payload: { time: "08:00" }, expectation: ({ state }) => expect(state.canSave).toBe(false) });
});

test("given the tab bar, each tab keeps its own screens", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  await session.do({ action: "OpenReminders", expectation: ({ name }) => expect(name).toBe("Reminders") });
  const medications = await selectTab(session, "medications", "Medications");
  expect(medications.path).toEqual(["App", "TabNavigator", "StackNavigator", "Medications"]);
  await selectTab(session, "history", "History");
  // Back on Today, the pushed Reminders screen is still there.
  await selectTab(session, "today", "Reminders");
  await session.do({ action: "selectTab", payload: { tab: "settings" }, expectation: ({ name }) => expect(name).toBe("Reminders") });
  await session.do({ action: "goBack", expectation: ({ name }) => expect(name).toBe("Today") });
});
