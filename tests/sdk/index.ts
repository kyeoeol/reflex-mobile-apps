// The Reflex test SDK. Behavior tests import only from here:
//
//   import { test, expect, createBehaviorSession, fixture } from "../sdk";
//
// Tests see exactly what the UI sees (node name, state, actions) and nothing else:
// no pixels, no internals, no mocks, no sleeps, zero network.

export { describe, expect, platforms, target, test } from "./harness";
export {
  type BehaviorSession,
  createBehaviorSession,
  fixture,
  type FixtureRef,
  type SessionOptions,
  type Step,
} from "./session";
export type { ActionInfo, NodeSnapshot, Platform } from "./protocol";
