// REFERENCE FEATURE: delete with the rest of "Sample" once the first real feature lands.
//
// One file, both platforms. Each test reads like a transcript of what a user did and saw.

import { type BehaviorSession, createBehaviorSession, expect, fixture, test } from "../sdk";

// Every detail journey starts on the list and opens one item.
async function openItem(session: BehaviorSession, id = "1") {
  await session.precondition("SampleList");
  await session.do({
    action: "SelectItem",
    payload: { id },
    expectation: ({ name, state }) => {
      expect(name).toBe("SampleDetail"); // we navigated...
      expect(state.itemId).toBe(id); // ...to the right item
      expect(state.isLoading).toBe(false); // ...and it finished loading
    },
  });
}

test("given the list loads, the user sees every item", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Sample.default")] });
  await session.precondition("SampleList");
  const node = await session.do({
    action: "Refresh",
    expectation: ({ state }) => {
      expect(state.isLoading).toBe(false);
      expect(state.items).toEqual([
        { id: "1", title: "First item", count: 2 },
        { id: "2", title: "Second item", count: 0 },
      ]);
    },
  });
  expect(node.state.loadError).toBeNull();
});

test("given save succeeds, the user can adjust the count and save it", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Sample.default")] });
  await openItem(session);

  await session.do({ action: "Decrement", expectation: ({ state }) => expect(state.count).toBe(1) });
  await session.do({
    action: "Decrement",
    expectation: ({ state }) => {
      expect(state.count).toBe(0);
      expect(state.canDecrement).toBe(false);
    },
  });
  // Decrement is always listed; the ViewModel decides it does nothing at zero.
  await session.do({ action: "Decrement", expectation: ({ state }) => expect(state.count).toBe(0) });
  // Actions with an input take a JSON payload. Negative counts are ignored by rule.
  await session.do({ action: "SetCount", payload: { count: -5 }, expectation: ({ state }) => expect(state.count).toBe(0) });
  await session.do({ action: "SetCount", payload: { count: 40 }, expectation: ({ state }) => expect(state.count).toBe(40) });
  await session.do({
    action: "Save",
    expectation: ({ state }) => {
      expect(state.isSaved).toBe(true);
      expect(state.isSaving).toBe(false);
      expect(state.saveError).toBeNull();
    },
  });
  // Editing after a save makes it unsaved again.
  await session.do({ action: "Increment", expectation: ({ state }) => expect(state.isSaved).toBe(false) });
});

test("given save fails, the user sees an error and the count stays unsaved", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Sample.saveFails")] });
  await openItem(session);

  await session.do({ action: "Increment", expectation: ({ state }) => expect(state.count).toBe(3) });
  // isSaved is already false before saving, so wait for the visible failure instead.
  await session.do({
    action: "Save",
    expectation: ({ state }) => {
      expect(state.saveError).toBe("Couldn't save. Try again.");
      expect(state.isSaved).toBe(false);
      expect(state.isSaving).toBe(false);
    },
  });
});

test("given the list fails to load once, the user sees an error and can retry", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Sample.listFailsOnce")] });
  await session.precondition("SampleList", ({ state }) => {
    expect(state.loadError).toBe("Couldn't load items.");
    expect(state.items).toEqual([]);
  });
  await session.do({
    action: "Refresh",
    expectation: ({ state }) => {
      expect(state.isLoading).toBe(false);
      expect(state.items).toHaveLength(2);
      expect(state.loadError).toBeNull();
    },
  });
});

test("given a deep link to an item, the user lands on it and can go back to the list", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Sample.default")], deeplink: "app://sample/2" });
  const detail = await session.precondition("SampleDetail");
  expect(detail.path).toEqual(["App", "StackNavigator", "SampleDetail"]);
  expect(detail.actions.map((a) => a.title)).toContain("goBack");

  await session.do({
    action: "goBack",
    expectation: ({ name, state }) => {
      expect(name).toBe("SampleList");
      expect(state.items).toHaveLength(2);
    },
  });
  // goBack is still listed on the root screen; it does nothing there.
  await session.do({ action: "goBack", expectation: ({ name }) => expect(name).toBe("SampleList") });
});
