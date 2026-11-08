---
name: reflex-explore
description: Drive the app headlessly through the Reflex CLI (ls, do, tree) to see what a screen shows and which actions it accepts, without writing a test, rendering UI, or using a simulator. Also covers driving a running DEBUG build (--live) and serving a fixture core over the socket. Use to investigate behavior, reproduce a bug, check a change, or answer "what happens if the user…".
---

# Exploring the app with the CLI

```
./reflex cli <ios|android> [--fixture Domain.scenario]... [--deeplink <url>] [--json]
./reflex cli <ios|android> --live [--port 7777] [--json]
```

Commands are read from stdin, one per line, so they work interactively or piped:

| Command | Effect |
|---|---|
| `ls` | focused screen: name, state (one-line JSON), actions (`Title {inputField, …}`; lent actions last) |
| `do <Action> [json]` | runs one action exactly like a user interaction; prints nothing on success |
| `tree` | the whole chain root → focus, with each node's state and actions (`(lent)` marked) |
| `quit` | exit |

Agents: pipe a script and read the transcript.

```
$ printf 'ls\ndo SelectItem {"id": "1"}\nls\ndo SetCount {"count": "x"}\nquit\n' | ./reflex cli ios --fixture Sample.default
SampleList
  state   {"items": [{"id": "1", "title": "First item", "count": 2}, ...], "isLoading": false, "loadError": null}
  actions SelectItem {id}, Refresh, goBack
SampleDetail
  state   {"itemId": "1", "title": "", "count": 0, "canDecrement": false, "isLoading": true, ...}   ← still loading
  actions Increment, Decrement, SetCount {count}, Save, goBack
error: Invalid payload for SetCount: /count must be integer. Schema: {"count": integer}
```

## Reading the output

- `do` returns as soon as the action is dispatched; async results (fixture responses) land shortly after. If an
  `ls` right after a `do` shows stale loading state, `ls` again. Tests handle this by polling; the CLI doesn't.
- `error: Action 'X' is not available on Y. Available: …`: X isn't on the focused node or lent by an ancestor
  (exactly like a button that isn't on screen).
- `error: Invalid payload …`: the payload failed the action's schema before reaching the ViewModel.
- `! fixture_miss: <Operation> {variables}`: the journey needs a fixture entry (`reflex-add-fixture`).
- `! network_violation: …`: something bypassed the GraphQL edge.
- `--json` prints raw protocol responses/events (NDJSON), handy for exact state diffs between platforms:
  run the same script with `ios` and `android` and `diff` the outputs.

## Live builds

- iOS: run the DEBUG app in a simulator (`./reflex app ios`, then run from Xcode); it serves 127.0.0.1:7777.
- Android: install the debug APK (`./reflex app android`), then `adb forward tcp:7777 tcp:7777`.
- `./reflex cli <platform> --live` now drives the same ViewModels the screen is bound to: the UI updates as you type.
- No device? `./reflex serve <platform> --fixture Sample.default [--port 7777]` serves a fixture-built core over the
  same live protocol (one session that persists across connections, like a running app).
