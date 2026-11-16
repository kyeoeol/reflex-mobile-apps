// Health Beta: adding, editing, archiving and deleting medications (docs/health/CONTRACT.md §3).
//
// Fixtures put "now" at Monday 2026-11-16 09:00 in Asia/Seoul.

import { createBehaviorSession, expect, fixture, test } from "../sdk";
import { openMedication, openReminders, openToday, selectTab, todayDose, todayDoses } from "./health";

test("given no medications, the user adds one with two daily times and it shows up everywhere", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.empty")] });
  await openToday(session);
  await session.do({ action: "AddMedication", expectation: ({ name }) => expect(name).toBe("MedicationEditor") });
  await session.do({ action: "SetName", payload: { name: " Vitamin D " }, expectation: ({ state }) => expect(state.name).toBe(" Vitamin D ") });
  await session.do({ action: "SetKind", payload: { kind: "supplement" }, expectation: ({ state }) => expect(state.kind).toBe("supplement") });
  await session.do({ action: "SetDose", payload: { dose: "1 capsule" }, expectation: ({ state }) => expect(state.dose).toBe("1 capsule") });
  await session.do({ action: "AddTime", payload: { time: "20:00" }, expectation: ({ state }) => expect(state.times).toEqual(["20:00"]) });
  await session.do({
    action: "AddTime",
    payload: { time: "08:00" },
    expectation: ({ state }) => {
      expect(state.times).toEqual(["08:00", "20:00"]);
      expect(state.canSave).toBe(true);
      expect(state.nameError).toBeNull();
    },
  });

  // Saving goes back to Today; the first scheduled medication asks for notification permission (granted here).
  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("Today");
      expect(state.groups.map((g: any) => g.key)).toEqual(["2026-11-16T08:00", "2026-11-16T20:00"]);
      expect(todayDoses(state).map((d) => [d.name, d.dose, d.status])).toEqual([
        ["Vitamin D", "1 capsule", "due"],
        ["Vitamin D", "1 capsule", "upcoming"],
      ]);
      expect(state.progressLabel).toBe("0 of 2 taken");
      expect(state.message).toBeNull();
      expect(state.reminderBanner).toBeNull();
    },
  });
  await openReminders(session, ({ state }) => {
    expect(state.permission).toBe("authorized");
    expect(state.reminders[0]).toEqual({
      id: "dose:2026-11-16T20:00", kind: "dose", fireAt: "2026-11-16T20:00:00+09:00",
      title: "Time for Vitamin D", body: "Vitamin D (1 capsule)",
    });
    // Today 20:00 (+ follow-up), six full days, and next Monday's 08:00 (+ follow-up) inside the 7-day window.
    expect(state.reminders).toHaveLength(28);
  });
  await selectTab(session, "medications", "Medications");
  const medications = await session.ls();
  expect(medications.state.isEmpty).toBe(false);
  expect(medications.state.active).toEqual([
    { id: expect.any(String), name: "Vitamin D", kind: "supplement", dose: "1 capsule", schedule: "Every day at 08:00, 20:00" },
  ]);
});

test("given invalid input, the user sees why a medication can't be saved", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  await selectTab(session, "medications", "Medications");
  await session.do({ action: "Add", expectation: ({ name }) => expect(name).toBe("MedicationEditor") });

  // Errors appear only once the user tries to save.
  const editor = await session.ls();
  expect(editor.state).toMatchObject({ nameError: null, timesError: null, canSave: false });
  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("MedicationEditor");
      expect(state).toMatchObject({
        nameError: "Name is required", timesError: "Add at least one time", weekdaysError: null, datesError: null,
        timeError: null, canSave: false,
      });
    },
  });
  await session.do({
    action: "SetName",
    payload: { name: "  metformin " },
    expectation: ({ state }) => expect(state.nameError).toBe("A medication with this name already exists"),
  });
  // Archived medications (Zinc) don't block their name.
  await session.do({ action: "SetName", payload: { name: "zinc" }, expectation: ({ state }) => expect(state.nameError).toBeNull() });
  await session.do({ action: "SetName", payload: { name: "Magnesium" }, expectation: ({ state }) => expect(state.nameError).toBeNull() });

  await session.do({
    action: "AddTime",
    payload: { time: "8am" },
    expectation: ({ state }) => {
      expect(state.timeError).toBe("Enter a time as HH:mm");
      expect(state.times).toEqual([]);
    },
  });
  await session.do({
    action: "AddTime",
    payload: { time: "21:00" },
    expectation: ({ state }) => {
      expect(state.times).toEqual(["21:00"]);
      expect(state.timeError).toBeNull();
      expect(state.timesError).toBeNull();
    },
  });
  await session.do({
    action: "AddTime",
    payload: { time: "21:00" },
    expectation: ({ state }) => {
      expect(state.timeError).toBe("Each time can only be added once");
      expect(state.times).toEqual(["21:00"]);
    },
  });
  await session.do({ action: "AddTime", payload: { time: "07:15" }, expectation: ({ state }) => expect(state.times).toEqual(["07:15", "21:00"]) });
  await session.do({ action: "RemoveTime", payload: { time: "21:00" }, expectation: ({ state }) => expect(state.times).toEqual(["07:15"]) });

  for (const weekday of [1, 2, 3, 4, 5, 6]) {
    await session.do({ action: "ToggleWeekday", payload: { weekday }, expectation: ({ state }) => expect(state.weekdays).not.toContain(weekday) });
  }
  await session.do({
    action: "ToggleWeekday",
    payload: { weekday: 7 },
    expectation: ({ state }) => {
      expect(state.weekdays).toEqual([]);
      expect(state.weekdaysError).toBe("Pick at least one day");
      expect(state.canSave).toBe(false);
    },
  });
  await session.do({ action: "ToggleWeekday", payload: { weekday: 9 }, expectation: ({ state }) => expect(state.weekdays).toEqual([]) });
  await session.do({
    action: "ToggleWeekday",
    payload: { weekday: 3 },
    expectation: ({ state }) => {
      expect(state.weekdays).toEqual([3]);
      expect(state.weekdaysError).toBeNull();
    },
  });

  await session.do({
    action: "SetEndDate",
    payload: { date: "2026-11-10" },
    expectation: ({ state }) => {
      expect(state.endDate).toBe("2026-11-10");
      expect(state.datesError).toBe("End date can't be before start date");
    },
  });
  await session.do({ action: "SetEndDate", payload: { date: "11/30" }, expectation: ({ state }) => expect(state.endDate).toBe("2026-11-10") });
  await session.do({
    action: "SetEndDate",
    payload: { date: null },
    expectation: ({ state }) => {
      expect(state.endDate).toBeNull();
      expect(state.datesError).toBeNull();
      expect(state.canSave).toBe(true);
    },
  });
  await session.do({ action: "SetKind", payload: { kind: "vitamin" }, expectation: ({ state }) => expect(state.kind).toBe("medication") });

  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("Medications");
      expect(state.active.map((m: any) => m.name)).toEqual(["Ibuprofen", "Magnesium", "Metformin", "Omega-3", "Vitamin D"]);
      expect(state.active[1]).toMatchObject({ kind: "medication", dose: "", schedule: "Wed at 07:15" });
    },
  });
});

test("given an as-needed medication, the user doesn't need times or days to save it", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  await selectTab(session, "medications", "Medications");
  await session.do({ action: "Add", expectation: ({ name }) => expect(name).toBe("MedicationEditor") });
  await session.do({ action: "SetName", payload: { name: "Antacid" }, expectation: ({ state }) => expect(state.name).toBe("Antacid") });
  await session.do({
    action: "SetAsNeeded",
    payload: { asNeeded: true },
    expectation: ({ state }) => {
      expect(state.asNeeded).toBe(true);
      expect(state.canSave).toBe(true);
    },
  });
  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("Medications");
      expect(state.active[0]).toMatchObject({ name: "Antacid", schedule: "As needed" });
    },
  });
  await selectTab(session, "today", "Today");
  const today = await session.ls();
  expect(today.state.asNeeded.map((m: any) => [m.name, m.summary])).toEqual([
    ["Antacid", "Not taken today"],
    ["Ibuprofen", "Taken once today, last at 08:30"],
  ]);
});

test("given twelve times already, the user can't add another one", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.empty")] });
  await openToday(session);
  await session.do({ action: "AddMedication", expectation: ({ name }) => expect(name).toBe("MedicationEditor") });
  for (let hour = 0; hour < 12; hour++) {
    const time = `${String(hour).padStart(2, "0")}:00`;
    await session.do({ action: "AddTime", payload: { time }, expectation: ({ state }) => expect(state.times).toContain(time) });
  }
  const full = await session.ls();
  expect(full.state.canAddTime).toBe(false);
  await session.do({
    action: "AddTime",
    payload: { time: "12:00" },
    expectation: ({ state }) => {
      expect(state.times).toHaveLength(12);
      expect(state.timeError).toBeNull();
    },
  });
});

test("given several medications, the user sees them by active, ended and archived", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  const node = await selectTab(session, "medications", "Medications");
  expect(node.state).toEqual({
    isLoading: false,
    isEmpty: false,
    active: [
      { id: "ibuprofen", name: "Ibuprofen", kind: "medication", dose: "200 mg", schedule: "As needed" },
      { id: "metformin", name: "Metformin", kind: "medication", dose: "500 mg", schedule: "Every day at 08:00, 20:00" },
      { id: "omega", name: "Omega-3", kind: "supplement", dose: "2 capsules", schedule: "Mon, Wed, Fri at 13:00" },
      { id: "vitd", name: "Vitamin D", kind: "supplement", dose: "1 capsule", schedule: "Every day at 08:00" },
    ],
    ended: [{ id: "amox", name: "Amoxicillin", kind: "medication", dose: "250 mg", schedule: "Every day at 09:00, 21:00" }],
    archived: [{ id: "zinc", name: "Zinc", kind: "supplement", dose: "1 tablet", schedule: "Every day at 21:00" }],
  });
  expect(node.actions.map((a) => a.title)).toEqual(["Add", "Open", "goBack", "selectTab"]);
});

test("given a medication with history, the user sees its schedule, adherence and past doses", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  const detail = await openMedication(session, "metformin");
  // Since 2026-11-01: 31 resolved doses (today's 20:00 is still upcoming), 2 taken → 6%.
  expect(detail.state).toMatchObject({
    name: "Metformin", kind: "medication", dose: "500 mg", notes: "", schedule: "Every day at 08:00, 20:00",
    startDate: "2026-11-01", endDate: null, status: "active", adherenceLabel: "6%",
    takenCount: 2, skippedCount: 0, missedCount: 29, canArchive: true, canRestore: false, isConfirmingDelete: false,
  });
  expect(detail.state.history).toHaveLength(31);
  expect(detail.state.history.slice(0, 3).map((d: any) => [d.id, d.status, d.takenAt])).toEqual([
    ["metformin@2026-11-16T08:00", "taken", "07:50"],
    ["metformin@2026-11-15T20:00", "missed", null],
    ["metformin@2026-11-15T08:00", "taken", "08:10"],
  ]);
  expect(detail.actions.map((a) => a.title)).toEqual(["Edit", "Archive", "Restore", "Delete", "CancelDelete", "ConfirmDelete", "goBack", "selectTab"]);

  await session.do({ action: "goBack", expectation: ({ name }) => expect(name).toBe("Medications") });
  await session.do({
    action: "Open",
    payload: { id: "ibuprofen" },
    expectation: ({ state }) => {
      expect(state).toMatchObject({ schedule: "As needed", adherenceLabel: "No data yet", takenCount: 0, missedCount: 0 });
      expect(state.history).toEqual([
        {
          id: "log:l4", medicationId: "ibuprofen", name: "Ibuprofen", dose: "200 mg", date: "2026-11-16", time: null,
          status: "taken", takenAt: "08:30", timing: null, canTake: false, canSkip: false, canUndo: true,
        },
      ]);
    },
  });
  await session.do({ action: "goBack", expectation: ({ name }) => expect(name).toBe("Medications") });
  await session.do({ action: "Open", payload: { id: "amox" }, expectation: ({ state }) => expect(state).toMatchObject({ status: "ended", endDate: "2026-11-09" }) });
});

test("given a schedule change, past days keep the old time and reminders move to the new one", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openMedication(session, "vitd");
  await session.do({
    action: "Edit",
    expectation: ({ name, state }) => {
      expect(name).toBe("MedicationEditor");
      expect(state).toMatchObject({
        mode: "edit", medicationId: "vitd", name: "Vitamin D", kind: "supplement", dose: "1 capsule", times: ["08:00"],
        weekdays: [1, 2, 3, 4, 5, 6, 7], startDate: "2026-11-01", canSave: true,
      });
    },
  });
  await session.do({ action: "RemoveTime", payload: { time: "08:00" }, expectation: ({ state }) => expect(state.times).toEqual([]) });
  await session.do({ action: "AddTime", payload: { time: "09:00" }, expectation: ({ state }) => expect(state.times).toEqual(["09:00"]) });
  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("MedicationDetail");
      expect(state.schedule).toBe("Every day at 09:00");
      expect(state.history.slice(0, 2).map((d: any) => [d.id, d.status])).toEqual([
        ["vitd@2026-11-16T09:00", "due"],
        ["vitd@2026-11-15T08:00", "taken"],
      ]);
    },
  });

  await selectTab(session, "today", "Today");
  const today = await session.ls();
  expect(today.state.groups.map((g: any) => g.key)).toEqual(["2026-11-16T08:00", "2026-11-16T09:00", "2026-11-16T13:00", "2026-11-16T20:00"]);
  expect(today.state.groups[0].doses.map((d: any) => d.name)).toEqual(["Metformin"]);
  expect(todayDose(today.state, "vitd@2026-11-16T09:00").status).toBe("due");

  await openReminders(session, ({ state }) => {
    const byId = Object.fromEntries(state.reminders.map((r: any) => [r.id, r]));
    expect(byId["dose:2026-11-17T08:00"].body).toBe("Metformin (500 mg)");
    expect(byId["dose:2026-11-17T09:00"]).toMatchObject({ title: "Time for Vitamin D", fireAt: "2026-11-17T09:00:00+09:00" });
  });
});

test("given a medication is archived, it leaves Today and its reminders until the user restores it", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openMedication(session, "vitd");
  await session.do({
    action: "Archive",
    expectation: ({ state }) => expect(state).toMatchObject({ status: "archived", canArchive: false, canRestore: true }),
  });
  await session.do({ action: "Archive", expectation: ({ state }) => expect(state.status).toBe("archived") });
  await session.do({
    action: "goBack",
    expectation: ({ name, state }) => {
      expect(name).toBe("Medications");
      expect(state.archived.map((m: any) => m.name)).toEqual(["Vitamin D", "Zinc"]);
      expect(state.active.map((m: any) => m.name)).toEqual(["Ibuprofen", "Metformin", "Omega-3"]);
    },
  });

  await selectTab(session, "today", "Today");
  const today = await session.ls();
  expect(todayDoses(today.state).map((d) => d.name)).not.toContain("Vitamin D");
  expect(today.state.scheduledCount).toBe(3);
  await openReminders(session, ({ state }) => {
    expect(state.reminders.some((r: any) => r.body.includes("Vitamin D"))).toBe(false);
    expect(state.reminders.find((r: any) => r.id === "dose:2026-11-17T08:00")).toMatchObject({ title: "Time for Metformin" });
  });

  await selectTab(session, "medications", "Medications");
  await session.do({ action: "Open", payload: { id: "vitd" }, expectation: ({ name }) => expect(name).toBe("MedicationDetail") });
  await session.do({
    action: "Restore",
    expectation: ({ state }) => expect(state).toMatchObject({ status: "active", canArchive: true, canRestore: false }),
  });
  await selectTab(session, "today", "Reminders");
  await session.do({
    action: "goBack",
    expectation: ({ name, state }) => {
      expect(name).toBe("Today");
      expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("due");
      expect(state.scheduledCount).toBe(4);
    },
  });
});

test("given the user deletes a medication, it asks first and then removes it with its history", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openMedication(session, "metformin");
  // Nothing happens without asking first.
  await session.do({ action: "ConfirmDelete", expectation: ({ name, state }) => {
    expect(name).toBe("MedicationDetail");
    expect(state.isConfirmingDelete).toBe(false);
  } });
  await session.do({ action: "Delete", expectation: ({ state }) => expect(state.isConfirmingDelete).toBe(true) });
  await session.do({ action: "CancelDelete", expectation: ({ state }) => expect(state.isConfirmingDelete).toBe(false) });
  await session.do({ action: "Delete", expectation: ({ state }) => expect(state.isConfirmingDelete).toBe(true) });
  await session.do({
    action: "ConfirmDelete",
    expectation: ({ name, state }) => {
      expect(name).toBe("Medications");
      expect(state.active.map((m: any) => m.name)).toEqual(["Ibuprofen", "Omega-3", "Vitamin D"]);
    },
  });

  await selectTab(session, "today", "Today");
  const today = await session.ls();
  expect(todayDoses(today.state).map((d) => d.id)).toEqual(["vitd@2026-11-16T08:00", "omega@2026-11-16T13:00"]);
  expect(today.state.progressLabel).toBe("0 of 2 taken");
  await openReminders(session, ({ state }) => expect(state.reminders.some((r: any) => r.body.includes("Metformin"))).toBe(false));

  await selectTab(session, "history", "History");
  await session.do({
    action: "PreviousDay",
    expectation: ({ state }) => expect(state.doses.map((d: any) => d.name)).toEqual(["Vitamin D"]),
  });
});
