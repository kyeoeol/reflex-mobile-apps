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
      messageKind: "noMedications",
      reminderBanner: null,
      reminderBannerAction: null,
      groups: [],
      asNeeded: [],
      doseError: null,
    });
  });
  expect(today.path).toEqual(["App", "TabNavigator", "StackNavigator", "Today"]);
  expect(today.actions.map((a) => a.title)).toEqual([
    "Take", "TakeAt", "Skip", "Undo", "LogAsNeeded", "UndoAsNeeded", "TurnOnReminders", "OpenReminders",
    "AddMedication", "OpenMedication", "Retry", "goBack", "selectTab",
  ]);

  await session.do({
    action: "AddMedication",
    expectation: ({ name, state }) => {
      expect(name).toBe("MedicationEditor");
      // Save is tappable on a pristine form (the first attempt reveals what's missing), but it isn't valid yet.
      expect(state).toEqual({
        mode: "add", medicationId: null, name: "", kind: "medication", dose: "", notes: "", asNeeded: false,
        times: [], weekdays: [1, 2, 3, 4, 5, 6, 7], startDate: "2026-11-16", endDate: null,
        nameError: null, timesError: null, weekdaysError: null, datesError: null, timeError: null,
        canAddTime: true, canChangeAsNeeded: true, isValid: false, canSave: true, isSaving: false,
      });
    },
  });
  await session.do({ action: "goBack", expectation: ({ name }) => expect(name).toBe("Today") });
});

test("given scheduled doses, the user sees today's doses grouped by time with their progress", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session, ({ state }) => {
    expect(state).toMatchObject({
      date: "2026-11-16", progressLabel: "1 of 4 taken", takenCount: 1, scheduledCount: 4,
      message: null, messageKind: null, reminderBanner: null, reminderBannerAction: null, doseError: null,
    });
    // Archived (Zinc) and ended (Amoxicillin) medications never show up; yesterday's missed 20:00 dose is gone.
    expect(state.groups.map((g: any) => [g.key, g.label])).toEqual([
      ["2026-11-16T08:00", "08:00"], ["2026-11-16T13:00", "13:00"], ["2026-11-16T20:00", "20:00"],
    ]);
    expect(state.groups[0]).toEqual({
      key: "2026-11-16T08:00",
      date: "2026-11-16",
      time: "08:00",
      label: "08:00",
      doses: [
        {
          id: "metformin@2026-11-16T08:00", medicationId: "metformin", name: "Metformin", dose: "500 mg",
          date: "2026-11-16", time: "08:00", status: "taken", needsAction: false, takenAt: "07:50", timing: "onTime",
          canTake: false, canTakeAt: true, canSkip: true, canUndo: true,
        },
        {
          id: "vitd@2026-11-16T08:00", medicationId: "vitd", name: "Vitamin D", dose: "1 capsule",
          date: "2026-11-16", time: "08:00", status: "due", needsAction: true, takenAt: null, timing: null,
          canTake: true, canTakeAt: true, canSkip: true, canUndo: false,
        },
      ],
    });
    expect(todayDose(state, "omega@2026-11-16T13:00")).toMatchObject({ name: "Omega-3", status: "upcoming", needsAction: false, canTake: true });
    expect(todayDose(state, "metformin@2026-11-16T20:00")).toMatchObject({ status: "upcoming", canTake: true, canUndo: false });
    expect(state.asNeeded).toEqual([
      {
        medicationId: "ibuprofen", name: "Ibuprofen", dose: "200 mg", takenToday: 1, lastTakenAt: "08:30",
        summary: "Taken once today, last at 08:30", canUndo: true,
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
      expect(todayDose(state, id)).toMatchObject({ status: "taken", needsAction: false, takenAt: "09:00", timing: "onTime", canTake: false, canUndo: true });
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
      expect(todayDose(state, id)).toMatchObject({ status: "due", needsAction: true, takenAt: null, timing: null, canUndo: false });
      expect(state.progressLabel).toBe("1 of 4 taken");
    },
  });
  await session.do({
    action: "Skip",
    payload: { doseId: id },
    expectation: ({ state }) => {
      expect(todayDose(state, id)).toMatchObject({ status: "skipped", needsAction: false, takenAt: null, canTake: true, canSkip: false, canUndo: true });
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
  // 13 hours before the 20:00 dose is too far from it.
  await session.do({
    action: "TakeAt",
    payload: { doseId: "metformin@2026-11-16T20:00", takenAt: "2026-11-16T07:00" },
    expectation: ({ state }) => {
      expect(state.doseError).toBe("Time must be within 12 hours of the scheduled dose");
      expect(todayDose(state, "metformin@2026-11-16T20:00")).toMatchObject({ status: "upcoming" });
    },
  });
  // Now (09:00) is 11 hours before it: taking it now is allowed, and early.
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
      expect(state.messageKind).toBeNull();
    },
  });
  await session.do({
    action: "Take",
    payload: { doseId: "metformin@2026-11-16T20:00" },
    expectation: ({ state }) => {
      expect(state.message).toBe("All done for today");
      expect(state.messageKind).toBe("allDone");
      expect(state.progressLabel).toBe("3 of 4 taken");
      expect(todayDoses(state).every((d) => d.canUndo)).toBe(true);
    },
  });
});

test("given an as-needed medication, the user can log doses whenever they take one", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.asNeededOnly")] });
  await openToday(session, ({ state }) => {
    expect(state).toMatchObject({ message: "Nothing scheduled today", messageKind: "nothingToday", progressLabel: null, groups: [] });
    // No scheduled medication: nothing to remind about, so no banner.
    expect(state.reminderBanner).toBeNull();
    expect(state.asNeeded).toEqual([
      {
        medicationId: "ibuprofen", name: "Ibuprofen", dose: "200 mg", takenToday: 0, lastTakenAt: null,
        summary: "Not taken today", canUndo: false,
      },
    ]);
  });
  await session.do({
    action: "LogAsNeeded",
    payload: { medicationId: "ibuprofen" },
    expectation: ({ state }) =>
      expect(state.asNeeded[0]).toMatchObject({ takenToday: 1, lastTakenAt: "09:00", summary: "Taken once today, last at 09:00", canUndo: true }),
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
    expectation: ({ state }) => expect(state.asNeeded[0]).toMatchObject({ takenToday: 0, summary: "Not taken today", canUndo: false }),
  });
  // Nothing left to undo, and unknown medications are ignored.
  await session.do({ action: "UndoAsNeeded", payload: { medicationId: "ibuprofen" }, expectation: ({ state }) => expect(state.asNeeded[0].takenToday).toBe(0) });
  await session.do({ action: "LogAsNeeded", payload: { medicationId: "nope" }, expectation: ({ state }) => expect(state.asNeeded).toHaveLength(1) });
});

test("given a late-night dose, after midnight the user still sees it under yesterday and can take it", async () => {
  // Now: Tuesday 2026-11-17 00:15; Melatonin is due every day at 23:30.
  await using session = createBehaviorSession({ fixtures: [fixture("Health.lateNight")] });
  await openToday(session, ({ state }) => {
    expect(state.date).toBe("2026-11-17");
    expect(state.groups.map((g: any) => [g.date, g.time, g.label])).toEqual([
      ["2026-11-16", "23:30", "Yesterday, 23:30"],
      ["2026-11-17", "23:30", "23:30"],
    ]);
    expect(todayDose(state, "melatonin@2026-11-16T23:30")).toMatchObject({ status: "due", needsAction: true });
    // Progress only counts today's own doses.
    expect(state.progressLabel).toBe("0 of 1 taken");
  });
  await session.do({
    action: "Take",
    payload: { doseId: "melatonin@2026-11-16T23:30" },
    expectation: ({ state }) => {
      expect(todayDose(state, "melatonin@2026-11-16T23:30")).toMatchObject({ status: "taken", takenAt: "00:15", timing: "onTime" });
      expect(state.progressLabel).toBe("0 of 1 taken");
      expect(state.messageKind).toBeNull();
    },
  });
});

test("given time passes, a due dose turns missed but stays on Today and can still be taken", async () => {
  // Now 09:55; the 08:00 Vitamin D turns missed at 10:00.
  await using session = createBehaviorSession({ fixtures: [fixture("Health.almostMissed")] });
  await openToday(session, ({ state }) => expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("due"));
  await session.device({ now: "2026-11-16T10:00:00" });
  await openToday(session, ({ state }) => {
    expect(todayDose(state, "vitd@2026-11-16T08:00")).toMatchObject({ status: "missed", needsAction: true, canTake: true });
    expect(state.progressLabel).toBe("1 of 4 taken");
  });
  await session.do({
    action: "Take",
    payload: { doseId: "vitd@2026-11-16T08:00" },
    expectation: ({ state }) => expect(todayDose(state, "vitd@2026-11-16T08:00")).toMatchObject({ status: "taken", takenAt: "10:00", timing: "late" }),
  });
});

test("given midnight passes while the app is open, Today moves to the new day", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  await session.device({ now: "2026-11-16T23:59:00" });
  await openToday(session, ({ state }) => {
    expect(state.date).toBe("2026-11-16");
    expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("missed");
  });
  await session.device({ now: "2026-11-17T00:00:00" });
  await openToday(session, ({ state }) => {
    expect(state.date).toBe("2026-11-17");
    // Tuesday: no Omega-3; yesterday's 20:00 dose is past its missed time, so it isn't carried over.
    expect(state.groups.map((g: any) => g.key)).toEqual(["2026-11-17T08:00", "2026-11-17T20:00"]);
    expect(state.progressLabel).toBe("0 of 3 taken");
    expect(state.asNeeded[0]).toMatchObject({ name: "Ibuprofen", takenToday: 0 });
  });
});

test("given stored data that can't be read, the user sees an error instead of an empty app", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.unreadableData")] });
  await openToday(session, ({ state }) => {
    expect(state).toMatchObject({ message: "Couldn't load your data", messageKind: "loadError", groups: [], reminderBanner: null });
  });
  // Retrying reads the same unreadable data.
  await session.do({ action: "Retry", expectation: ({ state }) => expect(state.messageKind).toBe("loadError") });
  // Nothing can be written over the unreadable data.
  await selectTab(session, "medications", "Medications");
  await session.do({ action: "Add", expectation: ({ name }) => expect(name).toBe("MedicationEditor") });
  await session.do({ action: "SetName", payload: { name: "Vitamin D" }, expectation: ({ state }) => expect(state.name).toBe("Vitamin D") });
  await session.do({
    action: "AddTime",
    payload: { time: "08:00" },
    expectation: ({ state }) => {
      expect(state.isValid).toBe(true);
      expect(state.canSave).toBe(false);
    },
  });
});

test("given saving fails, the user is told and the change is saved again on retry", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.saveFails")] });
  await openToday(session);
  await session.do({
    action: "Take",
    payload: { doseId: "vitd@2026-11-16T08:00" },
    expectation: ({ state }) => {
      expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("taken");
      expect(state).toMatchObject({ message: "Couldn't save your changes", messageKind: "saveError" });
    },
  });
  await session.device({ saveFails: false });
  await session.do({
    action: "Retry",
    expectation: ({ state }) => {
      expect(state.messageKind).toBeNull();
      expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("taken");
    },
  });
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
