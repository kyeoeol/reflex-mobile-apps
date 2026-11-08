// Behavior sessions: a fresh Core (headless) or the running app (live), driven through the protocol.

import { AsyncLocalStorage } from "node:async_hooks";
import { actionList, inlineJson } from "./format";
import { HostConnection, headlessHost, liveHost } from "./host";
import {
  type HostEvent,
  type NodeSnapshot,
  PLATFORM_LABEL,
  type Platform,
  ReflexProtocolError,
} from "./protocol";

export type Target = "headless" | "live";

export interface RunContext {
  platform: Platform;
  target: Target;
}

/** Set by the harness around every test body; `createBehaviorSession` reads it. */
export const runContext = new AsyncLocalStorage<RunContext>();

export interface FixtureRef {
  readonly name: string;
}

/** `fixture("Sample.saveFails")` → `fixtures/Sample/saveFails.json`. */
export function fixture(name: string): FixtureRef {
  if (!/^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)+$/.test(name)) throw new Error(`Bad fixture name '${name}' (expected 'Domain.scenario')`);
  return { name };
}

export interface SessionOptions {
  fixtures: FixtureRef[];
  /** Opened at session start, exactly like `AppRoot.open(url)` in the app. */
  deeplink?: string;
  /** How long `do`/`precondition` keep polling before failing. Default 2000ms. */
  timeoutMs?: number;
  /** Poll interval. Default 100ms. */
  intervalMs?: number;
}

export interface Step {
  action: string;
  payload?: Record<string, unknown>;
  /** Throw (via bun's `expect`) until the fresh state looks right. */
  expectation: (node: NodeSnapshot) => void;
}

export interface BehaviorSession extends AsyncDisposable {
  readonly platform: Platform;
  /** The focused node right now. */
  ls(): Promise<NodeSnapshot>;
  /** Waits until the focused node is `name` (and, optionally, until `expectation` passes on it). */
  precondition(name: string, expectation?: (node: NodeSnapshot) => void): Promise<NodeSnapshot>;
  /** Runs one action (exactly like a user interaction), then polls until `expectation` passes. */
  do(step: Step): Promise<NodeSnapshot>;
}

/**
 * Use with `await using` so the session is closed (and checked for violations) when the test ends:
 *
 *     await using session = createBehaviorSession({ fixtures: [fixture("Sample.default")] });
 */
export function createBehaviorSession(options: SessionOptions): BehaviorSession {
  const context = runContext.getStore();
  if (!context) throw new Error("createBehaviorSession must be called inside a test() from tests/sdk");
  return new Session(context, options);
}

/** A violation of the zero-network / full-fixture rules. Always fails the test. */
function describeViolation(event: HostEvent): string | null {
  switch (event.event) {
    case "network_violation":
      return `Network violation: ${event.request.method} ${event.request.url} — every external call needs a fixture (see the reflex-add-fixture skill)`;
    case "fixture_miss":
      return `Fixture miss: no fixture for operation '${event.operation}' with variables ${inlineJson(event.variables)} — add it to the session's fixtures`;
    default:
      return null;
  }
}

class Session implements BehaviorSession {
  readonly platform: Platform;
  private readonly timeoutMs: number;
  private readonly intervalMs: number;
  private id: Promise<string> | null = null;
  private host: HostConnection | null = null;
  private stopListening: (() => void) | null = null;
  private violations: string[] = [];
  private steps: string[] = [];
  private stepNumber = 0;

  constructor(private readonly context: RunContext, private readonly options: SessionOptions) {
    this.platform = context.platform;
    this.timeoutMs = options.timeoutMs ?? 2000;
    this.intervalMs = options.intervalMs ?? 100;
  }

  private start(): Promise<string> {
    this.id ??= (async () => {
      const live = this.context.target === "live";
      const host = await (live ? liveHost(this.platform) : headlessHost(this.platform));
      this.host = host;
      let sessionId = "live";
      this.stopListening = host.onEvent((event) => {
        const own = "session" in event && (event.session === sessionId || event.session === null);
        const message = own ? describeViolation(event) : null;
        if (message) this.violations.push(message);
      });
      if (!live) {
        const response = await host.call({
          cmd: "session.create",
          fixtures: this.options.fixtures.map((f) => f.name),
          deeplink: this.options.deeplink ?? null,
        });
        sessionId = response.session!;
      }
      return sessionId;
    })();
    return this.id;
  }

  async ls(): Promise<NodeSnapshot> {
    const session = await this.start();
    const response = await this.host!.call({ cmd: "ls", session });
    return response.node!;
  }

  async precondition(name: string, expectation?: (node: NodeSnapshot) => void): Promise<NodeSnapshot> {
    return this.poll(`precondition(${name})`, (node) => {
      if (node.name !== name) throw new Error(`Expected focused node ${name}, but it is ${node.name}`);
      expectation?.(node);
    });
  }

  async do(step: Step): Promise<NodeSnapshot> {
    const label = `do(${JSON.stringify(step.action)}${step.payload ? `, ${JSON.stringify(step.payload)}` : ""})`;
    const session = await this.start();
    const response = await this.host!.send({ cmd: "do", session, action: step.action, payload: step.payload });
    if (!response.ok) {
      // ACTION_NOT_AVAILABLE / INVALID_PAYLOAD: fail immediately, no polling.
      const node = await this.ls().catch(() => undefined);
      this.stepNumber++;
      throw this.failure(label, new ReflexProtocolError(response.error), node, 0, 0);
    }
    return this.poll(step.action, step.expectation, label);
  }

  private async poll(stepName: string, expectation: (node: NodeSnapshot) => void, label = stepName): Promise<NodeSnapshot> {
    this.stepNumber++;
    const started = performance.now();
    let polls = 0;
    let last: NodeSnapshot | undefined;
    let lastError: unknown;
    while (true) {
      last = await this.ls();
      polls++;
      this.throwIfViolated(label, last);
      try {
        expectation(last);
        this.steps.push(`${stepName} ✓`);
        return last;
      } catch (error) {
        lastError = error;
      }
      const elapsed = performance.now() - started;
      if (elapsed >= this.timeoutMs) throw this.failure(label, lastError, last, elapsed, polls);
      await Bun.sleep(this.intervalMs);
    }
  }

  private throwIfViolated(label: string, node?: NodeSnapshot) {
    if (this.violations.length) {
      this.stepNumber = Math.max(this.stepNumber, 1);
      throw this.failure(label, new Error(this.violations.join("\n")), node, 0, 0);
    }
  }

  /** Which step, which action, expected vs observed, the last state, the available actions. */
  private failure(label: string, cause: unknown, node: NodeSnapshot | undefined, elapsed: number, polls: number): Error {
    const reason = cause instanceof Error ? cause.message : String(cause);
    const lines = [
      `${PLATFORM_LABEL[this.platform]} — step ${this.stepNumber}: ${label}`,
      node ? `Focused: ${node.name}  (${node.path.join(" › ")})` : "Focused: (unknown)",
      "",
      ...reason.split("\n").map((l) => `  ${l}`),
      "",
    ];
    if (node) {
      lines.push(polls ? `Last state (after ${Math.round(elapsed)}ms, ${polls} polls):` : "State:");
      lines.push(`  ${inlineJson(node.state)}`);
      lines.push(`Available actions: ${actionList(node.actions)}`);
    }
    if (this.steps.length) lines.push(`Previous steps: ${this.steps.join(" → ")}`);
    const error = new Error(lines.join("\n"));
    if (cause instanceof Error) error.cause = cause;
    return error;
  }

  async [Symbol.asyncDispose](): Promise<void> {
    if (!this.id) return;
    const session = await this.id.catch(() => null);
    // Give in-flight fixture misses a moment to arrive before judging the session.
    await Bun.sleep(0);
    this.stopListening?.();
    if (session && this.context.target === "headless") {
      await this.host?.send({ cmd: "session.close", session }).catch(() => undefined);
    }
    if (this.violations.length) {
      throw new Error(`${PLATFORM_LABEL[this.platform]} — session ended with violations:\n  ${this.violations.join("\n  ")}`);
    }
  }
}
