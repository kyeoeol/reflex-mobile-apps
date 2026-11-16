// Health Beta: adding, editing, archiving and deleting medications (docs/health/CONTRACT.md §3).
//
// Fixtures put "now" at Monday 2026-11-16 09:00 in Asia/Seoul.

import { createBehaviorSession, expect, fixture, test } from "../sdk";
import { openMedication, openReminders, openToday, reminderIds, selectTab, todayDose, todayDoses } from "./health";

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
      expect(state).toMatchObject({ isValid: true, canSave: true, nameError: null });
    },
  });

  // Saving goes back to Today; the first scheduled medication asks for notification permission (granted here).
  // Added at 09:00, the 08:00 dose is still due (it would be missed only from 10:00).
  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("Today");
      expect(state.groups.map((g: any) => g.key)).toEqual(["2026-11-16T08:00", "2026-11-16T20:00"]);
      expect(todayDoses(state).map((d) => [d.name, d.dose, d.status])).toEqual([
        ["Vitamin D", "1 capsule", "due"],
        ["Vitamin D", "1 capsule", "upcoming"],
      ]);
      expect(state).toMatchObject({ progressLabel: "0 of 2 taken", message: null, reminderBanner: null });
    },
  });
  await openReminders(session, ({ state }) => {
    expect(state.permission).toBe("authorized");
    expect(state.reminders[0]).toEqual({
      id: "dose:2026-11-16T20:00", kind: "dose", fireAt: "2026-11-16T20:00:00+09:00",
      title: "Time for Vitamin D", body: "Vitamin D (1 capsule)",
    });
    // Today 20:00 (+ follow-up), six full days, next Monday's 08:00 (+ follow-up), then the window-end refresh.
    expect(state.reminders).toHaveLength(29);
    expect(state.reminders.at(-1)).toMatchObject({ id: "refresh", fireAt: "2026-11-23T09:00:00+09:00" });
  });
  const medications = await selectTab(session, "medications", "Medications");
  expect(medications.state.isEmpty).toBe(false);
  expect(medications.state.active).toEqual([
    { id: expect.any(String), name: "Vitamin D", kind: "supplement", dose: "1 capsule", schedule: "Every day at 08:00, 20:00" },
  ]);
});

test("given a medication added late in the day, its earlier times don't show up as already missed", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.empty")] });
  await openToday(session);
  await session.device({ now: "2026-11-16T15:00:00" });
  await session.do({ action: "AddMedication", expectation: ({ name }) => expect(name).toBe("MedicationEditor") });
  await session.do({ action: "SetName", payload: { name: "Iron" }, expectation: ({ state }) => expect(state.name).toBe("Iron") });
  await session.do({ action: "AddTime", payload: { time: "08:00" }, expectation: ({ state }) => expect(state.times).toEqual(["08:00"]) });
  await session.do({ action: "AddTime", payload: { time: "20:00" }, expectation: ({ state }) => expect(state.times).toEqual(["08:00", "20:00"]) });
  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("Today");
      expect(state.groups.map((g: any) => g.key)).toEqual(["2026-11-16T20:00"]);
      expect(state.progressLabel).toBe("0 of 1 taken");
    },
  });
});

test("given invalid input, the user sees why a medication can't be saved", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  await selectTab(session, "medications", "Medications");
  await session.do({ action: "Add", expectation: ({ name }) => expect(name).toBe("MedicationEditor") });

  // Errors appear only once the user tries to save; Save itself stays tappable.
  const editor = await session.ls();
  expect(editor.state).toMatchObject({ nameError: null, timesError: null, isValid: false, canSave: true });
  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("MedicationEditor");
      expect(state).toMatchObject({
        nameError: "Name is required", timesError: "Add at least one time", weekdaysError: null, datesError: null,
        timeError: null, isValid: false, canSave: true,
      });
    },
  });
  await session.do({
    action: "SetName",
    payload: { name: "  metformin " },
    expectation: ({ state }) => expect(state.nameError).toBe("A medication with this name already exists"),
  });
  // Archived (Zinc) and ended (Amoxicillin) medications don't block their names: a new course can be added.
  await session.do({ action: "SetName", payload: { name: "zinc" }, expectation: ({ state }) => expect(state.nameError).toBeNull() });
  await session.do({ action: "SetName", payload: { name: "Metformin XR" }, expectation: ({ state }) => expect(state.nameError).toBeNull() });
  await session.do({ action: "SetName", payload: { name: "amoxicillin" }, expectation: ({ state }) => expect(state.nameError).toBeNull() });
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
      expect(state.isValid).toBe(false);
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
      expect(state.isValid).toBe(true);
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

test("given names in different scripts and cases, the user sees one stable order and no look-alike duplicates", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.names")] });
  await openToday(session);
  const medications = await selectTab(session, "medications", "Medications");
  expect(medications.state.active.map((m: any) => m.name)).toEqual(["apple", "Bee", "Zinc", "Éclair", "오메가"]);
  await session.do({ action: "Add", expectation: ({ name }) => expect(name).toBe("MedicationEditor") });
  await session.do({ action: "Save", expectation: ({ state }) => expect(state.nameError).toBe("Name is required") });
  await session.do({
    action: "SetName",
    payload: { name: " ÉCLAIR " },
    expectation: ({ state }) => expect(state.nameError).toBe("A medication with this name already exists"),
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
    expectation: ({ state }) => expect(state).toMatchObject({ asNeeded: true, isValid: true }),
  });
  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("Medications");
      expect(state.active[0]).toMatchObject({ name: "Antacid", schedule: "As needed" });
    },
  });
  const today = await selectTab(session, "today", "Today");
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
    adherenceCaption: "2 of 31 doses taken in the last 30 days",
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
      expect(state).toMatchObject({ schedule: "As needed", adherenceLabel: "No data yet", adherenceCaption: null, takenCount: 0, missedCount: 0 });
      expect(state.history).toEqual([
        {
          id: "log:l4", medicationId: "ibuprofen", name: "Ibuprofen", dose: "200 mg", date: "2026-11-16", time: null,
          status: "taken", needsAction: false, takenAt: "08:30", timing: null,
          canTake: false, canTakeAt: false, canSkip: false, canUndo: true,
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
        weekdays: [1, 2, 3, 4, 5, 6, 7], startDate: "2026-11-01", canChangeAsNeeded: false, isValid: true, canSave: true,
      });
    },
  });
  // Turning a scheduled medication into an as-needed one isn't possible from edit mode.
  await session.do({ action: "SetAsNeeded", payload: { asNeeded: true }, expectation: ({ state }) => expect(state.asNeeded).toBe(false) });
  await session.do({ action: "RemoveTime", payload: { time: "08:00" }, expectation: ({ state }) => expect(state.times).toEqual([]) });
  await session.do({ action: "AddTime", payload: { time: "09:00" }, expectation: ({ state }) => expect(state.times).toEqual(["09:00"]) });
  // Vitamin D isn't logged today, so the new time applies from today.
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

  const today = await selectTab(session, "today", "Today");
  expect(today.state.groups.map((g: any) => g.key)).toEqual(["2026-11-16T08:00", "2026-11-16T09:00", "2026-11-16T13:00", "2026-11-16T20:00"]);
  expect(today.state.groups[0].doses.map((d: any) => d.name)).toEqual(["Metformin"]);
  expect(todayDose(today.state, "vitd@2026-11-16T09:00").status).toBe("due");

  await openReminders(session, ({ state }) => {
    const byId = Object.fromEntries(state.reminders.map((r: any) => [r.id, r]));
    expect(byId["dose:2026-11-17T08:00"].body).toBe("Metformin (500 mg)");
    expect(byId["dose:2026-11-17T09:00"]).toMatchObject({ title: "Time for Vitamin D", fireAt: "2026-11-17T09:00:00+09:00" });
  });
});

test("given today's dose is already logged, a schedule change starts tomorrow", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openMedication(session, "metformin");
  await session.do({ action: "Edit", expectation: ({ state }) => expect(state.times).toEqual(["08:00", "20:00"]) });
  await session.do({ action: "RemoveTime", payload: { time: "08:00" }, expectation: ({ state }) => expect(state.times).toEqual(["20:00"]) });
  await session.do({ action: "AddTime", payload: { time: "10:00" }, expectation: ({ state }) => expect(state.times).toEqual(["10:00", "20:00"]) });
  await session.do({
    action: "Save",
    expectation: ({ name, state }) => {
      expect(name).toBe("MedicationDetail");
      // The summary shows the newest schedule.
      expect(state.schedule).toBe("Every day at 10:00, 20:00");
    },
  });
  // Today keeps the 08:00 dose already taken and gets no second morning dose.
  const today = await selectTab(session, "today", "Today");
  expect(today.state.groups.map((g: any) => g.key)).toEqual(["2026-11-16T08:00", "2026-11-16T13:00", "2026-11-16T20:00"]);
  expect(today.state.progressLabel).toBe("1 of 4 taken");
  await openReminders(session, ({ state }) => {
    const byId = Object.fromEntries(state.reminders.map((r: any) => [r.id, r]));
    expect(byId["dose:2026-11-17T10:00"]).toMatchObject({ title: "Time for Metformin" });
    expect(byId["dose:2026-11-17T08:00"].body).toBe("Vitamin D (1 capsule)");
    expect(reminderIds(state)).toContain("dose:2026-11-16T20:00");
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

  const today = await selectTab(session, "today", "Today");
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
      // Restored at 09:00: today's 08:00 dose is back (still due until 10:00).
      expect(todayDose(state, "vitd@2026-11-16T08:00").status).toBe("due");
      expect(state.scheduledCount).toBe(4);
    },
  });
});

test("given the user deletes a medication, it asks first and then removes it everywhere", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  // Metformin is also open on the Today tab.
  await session.do({ action: "OpenMedication", payload: { id: "metformin" }, expectation: ({ name }) => expect(name).toBe("MedicationDetail") });
  await selectTab(session, "medications", "Medications");
  await session.do({ action: "Open", payload: { id: "metformin" }, expectation: ({ name }) => expect(name).toBe("MedicationDetail") });
  // Nothing happens without asking first.
  await session.do({
    action: "ConfirmDelete",
    expectation: ({ name, state }) => {
      expect(name).toBe("MedicationDetail");
      expect(state.isConfirmingDelete).toBe(false);
    },
  });
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

  // The detail open on the Today tab is gone too.
  const today = await selectTab(session, "today", "Today");
  expect(todayDoses(today.state).map((d) => d.id)).toEqual(["vitd@2026-11-16T08:00", "omega@2026-11-16T13:00"]);
  expect(today.state.progressLabel).toBe("0 of 2 taken");
  await openReminders(session, ({ state }) => expect(state.reminders.some((r: any) => r.body.includes("Metformin"))).toBe(false));

  await selectTab(session, "history", "History");
  await session.do({
    action: "PreviousDay",
    expectation: ({ state }) => expect(state.doses.map((d: any) => d.name)).toEqual(["Vitamin D"]),
  });
});

test("given a link to a medication, the user lands on its details", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")], deeplink: "healthbeta://medication/vitd" });
  const detail = await session.precondition("MedicationDetail", ({ state }) => expect(state.name).toBe("Vitamin D"));
  expect(detail.path).toEqual(["App", "TabNavigator", "StackNavigator", "MedicationDetail"]);
  await session.do({ action: "goBack", expectation: ({ name }) => expect(name).toBe("Medications") });
});

test("given a link to a medication that doesn't exist, the user lands on the medication list", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")], deeplink: "healthbeta://medication/nope" });
  await session.precondition("Medications", ({ state }) => {
    expect(state.isLoading).toBe(false);
    expect(state.active).toHaveLength(4);
  });
});
