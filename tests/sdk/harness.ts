// Platform fan-out on top of bun:test. One test() call registers one test per platform:
//
//   ✓ iOS: warm-up
//   ✓ Android: warm-up
//   ✓ iOS: given ..., the user can ...
//   ✓ Android: given ..., the user can ...
//
// REFLEX_PLATFORMS=ios,android (default both) picks platforms; REFLEX_TARGET=live drives running DEBUG builds.

import { test as bunTest } from "bun:test";
import { headlessHost, liveHost } from "./host";
import { PLATFORM_LABEL, PLATFORMS, type Platform } from "./protocol";
import { runContext, type Target } from "./session";

export { describe, expect } from "bun:test";

export const platforms: Platform[] = (process.env.REFLEX_PLATFORMS ?? PLATFORMS.join(","))
  .split(",")
  .map((p) => p.trim())
  .filter(Boolean)
  .map((p) => {
    if (!(PLATFORMS as readonly string[]).includes(p)) throw new Error(`Unknown platform '${p}' in REFLEX_PLATFORMS`);
    return p as Platform;
  });

export const target: Target = process.env.REFLEX_TARGET === "live" ? "live" : "headless";

const DEFAULT_TIMEOUT_MS = Number(process.env.REFLEX_TEST_TIMEOUT_MS ?? 15_000);
const WARMUP_TIMEOUT_MS = 60_000;

// Bun collects every test in a file before running any of them, so "a test has started running since
// the last registration" means we're registering tests for a new file: give it its own warm-up.
let needsWarmup = true;

function registerWarmups() {
  if (!needsWarmup) return;
  needsWarmup = false;
  for (const platform of platforms) {
    bunTest(
      `${PLATFORM_LABEL[platform]}: warm-up`,
      async () => {
        needsWarmup = true;
        // Absorbs host-process startup, JIT and first-session cost so journeys measure only themselves.
        const host = await (target === "live" ? liveHost(platform) : headlessHost(platform));
        if (target === "headless") {
          const created = await host.call({ cmd: "session.create", fixtures: [] });
          await host.call({ cmd: "ls", session: created.session! });
          await host.call({ cmd: "session.close", session: created.session! });
        } else {
          await host.call({ cmd: "ls", session: "live" });
        }
      },
      WARMUP_TIMEOUT_MS,
    );
  }
}

type Body = () => Promise<void> | void;

/** Registers `name` once per platform, as `"iOS: name"` and `"Android: name"`. */
export function test(name: string, body: Body, timeoutMs = DEFAULT_TIMEOUT_MS) {
  registerWarmups();
  for (const platform of platforms) {
    bunTest(
      `${PLATFORM_LABEL[platform]}: ${name}`,
      () => {
        needsWarmup = true;
        return runContext.run({ platform, target }, body);
      },
      timeoutMs,
    );
  }
}

test.skip = (name: string, _body: Body) => {
  for (const platform of platforms) bunTest.skip(`${PLATFORM_LABEL[platform]}: ${name}`, () => {});
};

/** Only for local debugging; never commit (`./reflex check` rejects it). */
test.only = (name: string, body: Body, timeoutMs = DEFAULT_TIMEOUT_MS) => {
  for (const platform of platforms) {
    bunTest.only(`${PLATFORM_LABEL[platform]}: ${name}`, () => runContext.run({ platform, target }, body), timeoutMs);
  }
};
