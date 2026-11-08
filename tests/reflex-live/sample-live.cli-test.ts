// The thin top of the test trophy: a handful of journeys against a running DEBUG build (real services).
// Run: start the app (iOS simulator, or Android with `adb forward tcp:7777 tcp:7777`), then
//      REFLEX_PLATFORMS=ios ./reflex test --live
// Without a device, `./reflex serve <platform> --fixture Sample.default` stands in for the app.
//
// REFERENCE FEATURE: delete with the rest of "Sample". Live tests can't assume backend data, so they
// assert only what holds for any data set.

import { createBehaviorSession, expect, test } from "../sdk";

test("given the app is running, the user starts on the item list and can refresh it", async () => {
  await using session = createBehaviorSession({ fixtures: [], timeoutMs: 10_000 });
  await session.precondition("SampleList", ({ state }) => expect(state.isLoading).toBe(false));
  const node = await session.do({ action: "Refresh", expectation: ({ state }) => expect(state.isLoading).toBe(false) });
  expect(node.actions.map((a) => a.title)).toEqual(["SelectItem", "Refresh", "goBack"]);
});
