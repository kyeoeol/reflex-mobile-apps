// Host processes and connections. One host process per platform stays alive for the whole run;
// every test creates a fresh session (a fresh Core) inside it.

import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import {
  type HostEvent,
  type Platform,
  type Request,
  type Response,
  ReflexProtocolError,
} from "./protocol";

export const REPO_ROOT = resolve(import.meta.dir, "../..");
export const FIXTURES_DIR = process.env.REFLEX_FIXTURES_DIR ?? join(REPO_ROOT, "fixtures");

type Pending = { resolve: (r: Response) => void; reject: (e: Error) => void };

/** A line-oriented NDJSON connection to a host (stdio or socket). */
export class HostConnection {
  private nextId = 1;
  private pending = new Map<number, Pending>();
  private listeners = new Set<(event: HostEvent) => void>();
  private buffer = "";
  private closedError: Error | null = null;
  readonly ready: Promise<HostEvent & { event: "ready" }>;
  private resolveReady!: (e: HostEvent & { event: "ready" }) => void;

  constructor(
    readonly platform: Platform,
    private readonly write: (line: string) => void,
    private readonly describe: () => string,
  ) {
    this.ready = new Promise((r) => (this.resolveReady = r));
  }

  /** Feed raw bytes from the host. */
  receive(chunk: string) {
    this.buffer += chunk;
    let newline: number;
    while ((newline = this.buffer.indexOf("\n")) >= 0) {
      const line = this.buffer.slice(0, newline).trim();
      this.buffer = this.buffer.slice(newline + 1);
      if (line) this.dispatch(line);
    }
  }

  private dispatch(line: string) {
    let message: any;
    try {
      message = JSON.parse(line);
    } catch {
      return; // not protocol output (stray log line)
    }
    if (typeof message.event === "string") {
      if (message.event === "ready") this.resolveReady(message);
      for (const listener of this.listeners) listener(message);
      return;
    }
    const pending = this.pending.get(message.id);
    if (pending) {
      this.pending.delete(message.id);
      pending.resolve(message);
    }
  }

  onEvent(listener: (event: HostEvent) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  /** Sends a request and resolves with the raw response (ok or not). */
  async send(request: Request): Promise<Response> {
    if (this.closedError) throw this.closedError;
    await this.ready;
    const id = this.nextId++;
    return new Promise<Response>((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.write(JSON.stringify({ id, ...request }) + "\n");
    });
  }

  /** Like `send`, but throws `ReflexProtocolError` on `ok: false`. */
  async call(request: Request): Promise<Response & { ok: true }> {
    const response = await this.send(request);
    if (!response.ok) throw new ReflexProtocolError(response.error);
    return response;
  }

  fail(error: Error) {
    this.closedError = error;
    for (const p of this.pending.values()) p.reject(error);
    this.pending.clear();
  }

  toString() {
    return this.describe();
  }
}

// ---------------------------------------------------------------------------------------------
// Headless hosts (stdio)

function toolchainJavaHome(): string | undefined {
  try {
    const record = JSON.parse(readFileSync(join(REPO_ROOT, ".reflex/toolchain.local.json"), "utf8"));
    return record?.env?.JAVA_HOME;
  } catch {
    return undefined;
  }
}

export function hostCommand(platform: Platform): string[] {
  if (platform === "ios") {
    const binary = process.env.REFLEX_IOS_HOST ?? join(REPO_ROOT, "ios/AppCLI/.build/debug/AppCLI");
    if (!existsSync(binary)) throw new Error(`iOS host not built (${binary}). Run: ./reflex build ios`);
    return [binary, "--fixtures-dir", FIXTURES_DIR];
  }
  const jar = process.env.REFLEX_ANDROID_HOST ?? join(REPO_ROOT, "android/cli/build/libs/cli.jar");
  if (!existsSync(jar)) throw new Error(`Android host not built (${jar}). Run: ./reflex build android`);
  const javaHome = process.env.JAVA_HOME ?? toolchainJavaHome();
  const java = javaHome ? join(javaHome, "bin/java") : "java";
  // Fast startup matters more than peak throughput for short journeys.
  return [java, "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-Xshare:auto", "-jar", jar, "--fixtures-dir", FIXTURES_DIR];
}

const hosts = new Map<Platform, Promise<HostConnection>>();
const children = new Set<{ kill(): void }>();
process.on("exit", () => children.forEach((c) => c.kill()));

/** The shared headless host for a platform, spawned on first use. */
export function headlessHost(platform: Platform): Promise<HostConnection> {
  let host = hosts.get(platform);
  if (!host) {
    host = spawnHost(platform);
    hosts.set(platform, host);
  }
  return host;
}

async function spawnHost(platform: Platform): Promise<HostConnection> {
  const command = hostCommand(platform);
  const child = Bun.spawn(command, { stdin: "pipe", stdout: "pipe", stderr: "pipe" });
  children.add(child);
  child.unref();
  let stderr = "";
  const connection = new HostConnection(
    platform,
    (line) => {
      child.stdin.write(line);
      child.stdin.flush();
    },
    () => `${platform} host (${command[0]})`,
  );
  (async () => {
    const decoder = new TextDecoder();
    for await (const chunk of child.stdout) connection.receive(decoder.decode(chunk, { stream: true }));
  })();
  (async () => {
    const decoder = new TextDecoder();
    for await (const chunk of child.stderr) stderr += decoder.decode(chunk, { stream: true });
  })();
  child.exited.then((code) => {
    hosts.delete(platform);
    connection.fail(new Error(`${platform} host exited with code ${code}${stderr ? `\n${stderr.trim()}` : ""}`));
  });
  const timeout = Bun.sleep(30_000).then(() => {
    throw new Error(`${platform} host did not send "ready" within 30s${stderr ? `\n${stderr.trim()}` : ""}`);
  });
  await Promise.race([connection.ready, timeout, child.exited.then(() => connection.send({ cmd: "ls", session: "" }))]);
  return connection;
}

// ---------------------------------------------------------------------------------------------
// Live hosts (socket to a running DEBUG build)

const liveHosts = new Map<string, Promise<HostConnection>>();

/**
 * Connects to a running DEBUG build's socket server.
 * iOS simulator: 127.0.0.1:7777 directly. Android: run `adb forward tcp:7777 tcp:7777` first.
 */
export function liveHost(platform: Platform, port = Number(process.env.REFLEX_LIVE_PORT ?? 7777)): Promise<HostConnection> {
  const key = `${platform}:${port}`;
  let host = liveHosts.get(key);
  if (!host) {
    host = connectLive(platform, port);
    liveHosts.set(key, host);
  }
  return host;
}

async function connectLive(platform: Platform, port: number): Promise<HostConnection> {
  let socketRef: { write(data: string): void; flush?(): void; end(): void } | null = null;
  const connection = new HostConnection(
    platform,
    (line) => socketRef!.write(line),
    () => `live ${platform} app on 127.0.0.1:${port}`,
  );
  const decoder = new TextDecoder();
  const socket = await Bun.connect({
    hostname: "127.0.0.1",
    port,
    socket: {
      data(_s, data) {
        connection.receive(decoder.decode(data, { stream: true }));
      },
      close() {
        connection.fail(new Error(`Live connection to 127.0.0.1:${port} closed`));
      },
      error(_s, error) {
        connection.fail(error);
      },
    },
  }).catch((e) => {
    throw new Error(
      `Can't reach a live ${platform} app on 127.0.0.1:${port} (${e.message}). Start a DEBUG build first` +
        (platform === "android" ? " and run `adb forward tcp:7777 tcp:7777`." : "."),
    );
  });
  socketRef = socket;
  socket.unref();
  children.add({ kill: () => socket.end() });
  const ready = await Promise.race([
    connection.ready,
    Bun.sleep(5_000).then(() => {
      throw new Error(`No "ready" from 127.0.0.1:${port}`);
    }),
  ]);
  if (ready.platform !== platform) {
    throw new Error(`The app on 127.0.0.1:${port} is ${ready.platform}, not ${platform}`);
  }
  return connection;
}
