---
name: reflex-write-behavior-test
description: How to write Reflex behavior tests, the single Bun test suite that drives both the iOS and the Android core headlessly. Covers the SDK API (createBehaviorSession, precondition, do, fixture), naming, grouping by journey, splitting only on setup, helpers, and what never to do (sleeps, mocks, internals). Use when writing or changing anything under tests/behavior or tests/reflex-live.
---

# Behavior tests

One file, both platforms. `test()` from the SDK registers every test once per platform ("iOS: …", "Android: …") and
each test gets a fresh Core built from its fixtures. Tests see exactly what the UI sees, the focused node's
`name`, `state` and `actions`, and nothing else.

Reference: `tests/behavior/sample.cli-test.ts`. SDK: `tests/sdk/index.ts` (import only from `"../sdk"`).

## Template

```ts
import { type BehaviorSession, createBehaviorSession, expect, fixture, test } from "../sdk";

// Shared entry paths are helpers. Every journey into Thing starts on the list.
async function openThing(session: BehaviorSession, id = "1") {
  await session.precondition("ThingList");
  await session.do({
    action: "SelectThing",
    payload: { id },
    expectation: ({ name, state }) => {
      expect(name).toBe("Thing");
      expect(state.thingId).toBe(id);
      expect(state.isLoading).toBe(false);
    },
  });
}

test("given the thing loads, the user can rename it and submit", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Thing.default")] });
  await openThing(session);
  await session.do({ action: "Rename", payload: { name: "New" }, expectation: ({ state }) => expect(state.name).toBe("New") });
  await session.do({ action: "Submit", expectation: ({ state }) => expect(state.isSubmitted).toBe(true) });
});

test("given submitting fails, the user sees an error and can retry", async () => {
  await using session = createBehaviorSession({ fixtures: [fixture("Thing.submitFails")] });
  // …
});
```

## API

- `createBehaviorSession({ fixtures, deeplink?, timeoutMs = 2000, intervalMs = 100 })`, always with `await using`
  (closes the session and fails on any `network_violation` / `fixture_miss`).
- `session.precondition(name, expectation?)` polls until the focused node is `name` (and the optional expectation
  passes). Use the expectation for initial-load state.
- `session.do({ action, payload?, expectation })` runs one action exactly like a user tap, then polls `ls` until the
  expectation passes (or fails after `timeoutMs` with the last state). `ACTION_NOT_AVAILABLE` / `INVALID_PAYLOAD`
  fail immediately.
- `session.ls()` returns the current snapshot (rarely needed).
- Lent actions (`goBack`) are called like any other action.

## Rules

1. **Names**: `given <precondition>, <what the user can do / sees>`.
2. **Group by journey**: all the steps of one journey in one test. Failure messages already say which step failed.
3. **Split on setup, not on assertions**: a new test only when it needs different fixtures (or a deep link).
4. **Read like a transcript** of what a user did and saw: no internal names, no service/class references.
5. **Wait for evidence, not absence**: if the expected state is already true before the action (e.g. `isSaved ===
   false`), assert on something that proves the path ran (`saveError`). If no such field exists, the screen is
   missing state: add it to the ViewModel.
6. Assert on everything the step is about, including that no-ops really are no-ops (e.g. Decrement at 0).
7. **Never**: sleeps/timeouts, mocks or spies, `test.only`, importing anything but `../sdk`, branching on
   `session.platform`, or a platform-specific expectation. If the platforms disagree, that's a parity bug
   (`reflex-parity`), not a test to special-case.

## Running

- `./reflex test` runs everything on both platforms (builds hosts first).
- `./reflex test tests/behavior/thing.cli-test.ts` runs one file.
- `REFLEX_PLATFORMS=ios ./reflex test …` runs one platform while iterating.
- Live layer: `tests/reflex-live/*.cli-test.ts` (same API; `fixtures: []`; assert only what holds for real data);
  `./reflex test --live` against a running DEBUG build, or against `./reflex serve <platform> --fixture …`.
