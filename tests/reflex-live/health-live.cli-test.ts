// The thin top of the test trophy: a handful of journeys against a running DEBUG build (real platform edges).
// Run: start the app (iOS simulator, or Android with `adb forward tcp:7777 tcp:7777`), then
//      REFLEX_PLATFORMS=ios ./reflex test --live
// Without a device, `./reflex serve <platform> --fixture Health.default` stands in for the app.
//
// Live tests can't assume what the user has stored, so they assert only what holds for any data.

import { createBehaviorSession, expect, test } from "../sdk";

test("given the app is running, the user starts on Today and can move between the tabs", async () => {
  await using session = createBehaviorSession({ fixtures: [], timeoutMs: 10_000 });
  const today = await session.precondition("Today", ({ state }) => expect(state.isLoading).toBe(false));
  expect(today.path).toEqual(["App", "TabNavigator", "StackNavigator", "Today"]);
  await session.do({ action: "selectTab", payload: { tab: "medications" }, expectation: ({ name }) => expect(name).toBe("Medications") });
  await session.do({ action: "selectTab", payload: { tab: "history" }, expectation: ({ name }) => expect(name).toBe("History") });
  await session.do({ action: "selectTab", payload: { tab: "today" }, expectation: ({ name }) => expect(name).toBe("Today") });
  await session.do({ action: "OpenReminders", expectation: ({ name }) => expect(name).toBe("Reminders") });
  await session.do({ action: "goBack", expectation: ({ name }) => expect(name).toBe("Today") });
});
