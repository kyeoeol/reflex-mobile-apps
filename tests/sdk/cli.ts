// The platform-agnostic Reflex CLI (`./reflex cli ...`). Agent-first: plain text, no TUI, no colors,
// no spinners, no wizards. Reads commands from stdin (interactive or piped):
//
//   ls                      the focused screen: name, state, actions
//   do <Action> [json]      run one action, exactly like a user interaction (prints nothing on success)
//   tree                    the chain from root to focus, with each node's state
//   quit                    exit
//
// Usage: bun tests/sdk/cli.ts <ios|android> [--fixture <Domain.scenario>]... [--deeplink <url>] [--json]
//        bun tests/sdk/cli.ts <ios|android> --live [--port 7777] [--json]

import { formatError, formatNode, formatTree, inlineJson } from "./format";
import { headlessHost, liveHost } from "./host";
import { PLATFORMS, type Platform, type Response } from "./protocol";

function usage(message?: string): never {
  if (message) console.error(`error: ${message}`);
  console.error(
    "usage: reflex cli <ios|android> [--fixture <Domain.scenario>]... [--deeplink <url>] [--json]\n" +
      "       reflex cli <ios|android> --live [--port 7777] [--json]",
  );
  process.exit(64);
}

const args = process.argv.slice(2);
const platform = args.shift() as Platform;
if (!PLATFORMS.includes(platform)) usage(`unknown platform '${platform ?? ""}'`);

const fixtures: string[] = [];
let deeplink: string | null = null;
let json = false;
let live = false;
let port = 7777;
while (args.length) {
  const arg = args.shift()!;
  if (arg === "--fixture" || arg === "--fixtures") fixtures.push(args.shift() ?? usage("--fixture needs a name"));
  else if (arg === "--deeplink") deeplink = args.shift() ?? usage("--deeplink needs a url");
  else if (arg === "--json") json = true;
  else if (arg === "--live") live = true;
  else if (arg === "--port") port = Number(args.shift() ?? usage("--port needs a number"));
  else usage(`unknown argument '${arg}'`);
}

const host = await (live ? liveHost(platform, port) : headlessHost(platform)).catch((e) => {
  console.error(`error: ${e.message}`);
  process.exit(1);
});

host.onEvent((event) => {
  if (event.event === "ready") return;
  if (json) console.log(JSON.stringify(event));
  else if (event.event === "fixture_miss") console.log(`! fixture_miss: ${event.operation} ${inlineJson(event.variables)}`);
  else if (event.event === "network_violation") console.log(`! network_violation: ${event.request.method} ${event.request.url}`);
  else if (event.event === "log") console.log(`! log ${event.level}: ${event.message}`);
});

let session = "live";
if (!live) {
  const created = await host.send({ cmd: "session.create", fixtures, deeplink });
  if (!created.ok) {
    console.error(formatError(created.error));
    process.exit(1);
  }
  session = created.session!;
}

const interactive = process.stdin.isTTY;
const prompt = () => interactive && process.stdout.write("> ");

async function focused() {
  const response = await host.send({ cmd: "ls", session });
  return response.ok ? response.node : undefined;
}

async function report(response: Response, render: (r: Response & { ok: true }) => string | null) {
  if (json) return console.log(JSON.stringify(response));
  if (response.ok) {
    const text = render(response);
    if (text) console.log(text);
  } else {
    console.log(formatError(response.error, response.error.code === "ACTION_NOT_AVAILABLE" ? await focused() : undefined));
  }
}

async function run(line: string): Promise<boolean> {
  const trimmed = line.trim();
  if (!trimmed) return true;
  const [command, ...rest] = trimmed.split(/\s+/);
  switch (command) {
    case "ls":
      await report(await host.send({ cmd: "ls", session }), (r) => formatNode(r.node!));
      return true;
    case "tree":
      await report(await host.send({ cmd: "tree", session }), (r) => formatTree(r.tree!));
      return true;
    case "do": {
      const action = rest[0];
      if (!action) {
        console.log("error: usage: do <Action> [json payload]");
        return true;
      }
      const raw = trimmed.slice(trimmed.indexOf(action) + action.length).trim();
      let payload: unknown;
      if (raw) {
        try {
          payload = JSON.parse(raw);
        } catch {
          console.log(`error: payload is not valid JSON: ${raw}`);
          return true;
        }
      }
      await report(await host.send({ cmd: "do", session, action, payload }), () => null);
      return true;
    }
    case "quit":
    case "exit":
      return false;
    case "help":
      console.log("commands: ls | do <Action> [json] | tree | quit");
      return true;
    default:
      console.log(`error: unknown command '${command}'. Commands: ls, do <Action> [json], tree, quit`);
      return true;
  }
}

prompt();
const decoder = new TextDecoder();
let buffered = "";
let keepGoing = true;
for await (const chunk of process.stdin) {
  buffered += decoder.decode(chunk, { stream: true });
  let newline: number;
  while (keepGoing && (newline = buffered.indexOf("\n")) >= 0) {
    const line = buffered.slice(0, newline);
    buffered = buffered.slice(newline + 1);
    keepGoing = await run(line);
    if (keepGoing) prompt();
  }
  if (!keepGoing) break;
}
if (keepGoing && buffered.trim()) await run(buffered);
if (!live) await host.send({ cmd: "session.close", session }).catch(() => undefined);
process.exit(0);
