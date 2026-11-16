// Shared entry paths and lookups for the Health Beta journeys (tests/behavior/health-*.cli-test.ts).
// Spec: docs/health/CONTRACT.md. Not a test file itself (no `.cli-test.ts` suffix).

import { type BehaviorSession, expect, type NodeSnapshot } from "../sdk";

type Json = Record<string, any>;

/** Every dose shown on Today, in display order (groups flattened). */
export function todayDoses(state: Json): Json[] {
  return (state.groups as Json[]).flatMap((group) => group.doses as Json[]);
}

/** One dose on Today by id; fails the expectation if it isn't shown. */
export function todayDose(state: Json, id: string): Json {
  const dose = todayDoses(state).find((d) => d.id === id);
  if (!dose) throw new Error(`Dose ${id} is not on Today (shown: ${todayDoses(state).map((d) => d.id).join(", ")})`);
  return dose;
}

/** The app starts on Today; wait for the stored data to load. */
export async function openToday(session: BehaviorSession, expectation?: (node: NodeSnapshot) => void): Promise<NodeSnapshot> {
  return session.precondition("Today", (node) => {
    expect(node.state.isLoading).toBe(false);
    expectation?.(node);
  });
}

/** Switch tabs with the tab bar. */
export async function selectTab(session: BehaviorSession, tab: "today" | "medications" | "history", screen: string) {
  return session.do({
    action: "selectTab",
    payload: { tab },
    expectation: ({ name, state }) => {
      expect(name).toBe(screen);
      expect(state.isLoading ?? false).toBe(false);
    },
  });
}

/** From the Medications tab, open one medication's detail. */
export async function openMedication(session: BehaviorSession, id: string) {
  await openToday(session);
  await selectTab(session, "medications", "Medications");
  return session.do({
    action: "Open",
    payload: { id },
    expectation: ({ name, state }) => {
      expect(name).toBe("MedicationDetail");
      expect(state.id).toBe(id);
    },
  });
}

/** From Today, open the Reminders screen. */
export async function openReminders(session: BehaviorSession, expectation?: (node: NodeSnapshot) => void) {
  return session.do({
    action: "OpenReminders",
    expectation: (node) => {
      expect(node.name).toBe("Reminders");
      expectation?.(node);
    },
  });
}

/** Ids of the pending reminders, in order. */
export function reminderIds(state: Json): string[] {
  return (state.reminders as Json[]).map((r) => r.id);
}
