// Health Beta: the History tab (docs/health/CONTRACT.md §3 History).
//
// Fixtures put "now" at Monday 2026-11-16 09:00 in Asia/Seoul.

import { createBehaviorSession, expect, fixture, test } from "../sdk";
import { openToday, selectTab } from "./health";

const ids = (state: any) => state.doses.map((d: any) => d.id);

test("given past days, the user can browse their history day by day", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  const history = await selectTab(session, "history", "History");
  expect(history.state).toMatchObject({
    isLoading: false, date: "2026-11-16", canGoNext: false, summaryLabel: "1 of 4 taken", message: null, doseError: null,
  });
  // Scheduled doses and as-needed logs, by time.
  expect(ids(history.state)).toEqual([
    "metformin@2026-11-16T08:00", "vitd@2026-11-16T08:00", "log:l4", "omega@2026-11-16T13:00", "metformin@2026-11-16T20:00",
  ]);
  expect(history.actions.map((a) => a.title)).toEqual([
    "PreviousDay", "NextDay", "SelectDate", "Take", "TakeAt", "Skip", "Undo", "goBack", "selectTab",
  ]);

  await session.do({ action: "NextDay", expectation: ({ state }) => expect(state.date).toBe("2026-11-16") });
  await session.do({
    action: "PreviousDay",
    expectation: ({ state }) => {
      expect(state).toMatchObject({ date: "2026-11-15", canGoNext: true, summaryLabel: "2 of 3 taken" });
      expect(state.doses.map((d: any) => [d.id, d.status, d.takenAt])).toEqual([
        ["metformin@2026-11-15T08:00", "taken", "08:10"],
        ["vitd@2026-11-15T08:00", "taken", "08:10"],
        ["metformin@2026-11-15T20:00", "missed", null],
      ]);
    },
  });
  // Archived since 11-10 (Zinc) and ended on 11-09 (Amoxicillin): gone from later days, kept before.
  await session.do({
    action: "SelectDate",
    payload: { date: "2026-11-12" },
    expectation: ({ state }) => {
      expect(ids(state)).toEqual(["metformin@2026-11-12T08:00", "vitd@2026-11-12T08:00", "metformin@2026-11-12T20:00"]);
      expect(state.summaryLabel).toBe("0 of 3 taken");
    },
  });
  await session.do({
    action: "SelectDate",
    payload: { date: "2026-11-05" },
    expectation: ({ state }) => {
      expect(state.doses.map((d: any) => [d.time, d.name])).toEqual([
        ["08:00", "Metformin"], ["08:00", "Vitamin D"], ["09:00", "Amoxicillin"],
        ["20:00", "Metformin"], ["21:00", "Amoxicillin"], ["21:00", "Zinc"],
      ]);
      expect(state.doses.every((d: any) => d.status === "missed")).toBe(true);
    },
  });
  await session.do({
    action: "SelectDate",
    payload: { date: "2026-10-20" },
    expectation: ({ state }) => {
      expect(state).toMatchObject({ date: "2026-10-20", doses: [], summaryLabel: null, message: "Nothing recorded on this day" });
    },
  });
  // The future and malformed dates are ignored.
  await session.do({ action: "SelectDate", payload: { date: "2026-12-01" }, expectation: ({ state }) => expect(state.date).toBe("2026-10-20") });
  await session.do({ action: "SelectDate", payload: { date: "Monday" }, expectation: ({ state }) => expect(state.date).toBe("2026-10-20") });
  await session.do({ action: "NextDay", expectation: ({ state }) => expect(state.date).toBe("2026-10-21") });
});

test("given a missed dose yesterday, the user can record it as taken later and undo it", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Health.default")] });
  await openToday(session);
  await selectTab(session, "history", "History");
  await session.do({ action: "PreviousDay", expectation: ({ state }) => expect(state.date).toBe("2026-11-15") });
  const missed = "metformin@2026-11-15T20:00";

  // Now is 13 hours after that dose: "taken now" is too far from it, the actual time is needed.
  await session.do({
    action: "Take",
    payload: { doseId: missed },
    expectation: ({ state }) => {
      expect(state.doseError).toBe("Time must be within 12 hours of the scheduled dose");
      // The screen said so already: "take now" isn't offered, "change time" is.
      expect(state.doses[2]).toMatchObject({ id: missed, status: "missed", needsAction: true, canTake: false, canTakeAt: true });
    },
  });
  await session.do({
    action: "TakeAt",
    payload: { doseId: missed, takenAt: "2026-11-15T21:15" },
    expectation: ({ state }) => {
      expect(state.doseError).toBeNull();
      expect(state.doses[2]).toMatchObject({ id: missed, status: "taken", takenAt: "21:15", timing: "late", canUndo: true });
      expect(state.summaryLabel).toBe("3 of 3 taken");
    },
  });
  await session.do({
    action: "Undo",
    payload: { doseId: missed },
    expectation: ({ state }) => {
      expect(state.doses[2]).toMatchObject({ status: "missed", takenAt: null });
      expect(state.summaryLabel).toBe("2 of 3 taken");
    },
  });

  // An as-needed log can be removed from History too.
  await session.do({ action: "NextDay", expectation: ({ state }) => expect(ids(state)).toContain("log:l4") });
  await session.do({ action: "Undo", payload: { doseId: "log:l4" }, expectation: ({ state }) => expect(ids(state)).not.toContain("log:l4") });
  const today = await selectTab(session, "today", "Today");
  expect(today.state.asNeeded[0]).toMatchObject({ name: "Ibuprofen", takenToday: 0, summary: "Not taken today" });
});
