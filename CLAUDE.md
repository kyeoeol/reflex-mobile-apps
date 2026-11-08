# Reflex mobile apps

Native iOS (Swift/SwiftUI) and Android (Kotlin/Compose) apps built on the **Reflex architecture**: everything except
the views lives in a headless core, every screen's ViewModel has the same strict shape and is exposed as JSON
(name · state · actions), and **one Bun test suite drives both cores**, so both apps are kept behaving the same.

The product is not decided yet. Whatever it becomes, it is built this way. The infrastructure is already in
place and working end to end; features plug into it.

All code, comments, docs, commit messages and skills in this repo are written in **English**.

## Required first step (every machine)

`.reflex/toolchain.local.json` records a verified toolchain (machine-local, gitignored). **If it doesn't exist, run
`./reflex setup` before anything else.** It checks Xcode, Swift, Bun, JDK 21, XcodeGen and the Android SDK,
installs whatever is missing through Homebrew, and writes the record. Once the record exists the check is not
repeated (`./reflex setup --force` re-checks). Every other `./reflex` command enforces this on its own.

## How work gets done

| When you are asked to… | Use skill |
|---|---|
| build or change **any** feature, screen, flow, or behavior (including the first real feature) | `reflex-feature` (always start here) |
| add a screen / ViewModel / node / route / view | `reflex-new-screen` |
| call a new GraphQL operation, or add a platform capability (storage, device, flags, push, camera…) | `reflex-add-edge` |
| add or change test data | `reflex-add-fixture` |
| write or change behavior tests | `reflex-write-behavior-test` |
| look at what a screen shows / try actions without writing a test | `reflex-explore` |
| a behavior test fails | `reflex-debug-failure` |
| a test passes on one platform but not the other | `reflex-parity` |

The order for any feature: contract → fixtures → behavior test → iOS core → Android core (same test, unchanged) →
`./reflex check` → views. A feature is done only when `./reflex ci` is green.

## Commands

| Command | What it does |
|---|---|
| `./reflex setup [--force]` | Verify/install the toolchain once per machine (see above) |
| `./reflex test [files…]` | Build both headless hosts, run `tests/behavior/**/*.cli-test.ts` on iOS **and** Android |
| `REFLEX_PLATFORMS=ios ./reflex test` | One platform only (while iterating; never the final check) |
| `./reflex check` | Architecture lint: core purity, unconditional nodes, single writer, node coverage, cross-platform parity, fixtures, test hygiene |
| `./reflex unit` | Unit tests for primitives (IntrospectionKit on both platforms) |
| `./reflex cli <ios\|android> --fixture Domain.scenario` | Drive a headless core: `ls`, `do Action {json}`, `tree`, `quit` (stdin, pipe-friendly) |
| `./reflex serve <ios\|android> --fixture …` | Serve one fixture core over the live socket protocol (live layer without a simulator) |
| `./reflex test --live` | `tests/reflex-live` against a running DEBUG build (127.0.0.1:7777) |
| `./reflex app <ios\|android>` | Build the app shells (XcodeGen + xcodebuild / Gradle `:app`) |
| `./reflex ci` | `check` + `unit` + `test`: the merge gate (CI: `.github/workflows/reflex.yml`) |

Bun only runs `*.cli-test.ts` when given explicit paths; always go through `./reflex test`.

## Repository map

```
ios/Packages/IntrospectionKit/   IntrospectionKit (node protocol, JSONValue, schemas, resolution) + ReflexHost (NDJSON host, fixtures, transports, network guard)
ios/Packages/AppCore/            iOS Core: Foundation/ (ViewModel, Platform protocols, GraphQL, StackNavigator), App/ (Core, AppRoot, Route), Features/<Feature>/
ios/AppCLI/                      headless iOS host (macOS executable): Core + fixture adapters over stdio
ios/AppMobile/                   SwiftUI app (project.yml → XcodeGen; .xcodeproj is generated, never edited)
android/introspection-kit/       Kotlin IntrospectionKit (+ IntrospectionRegistry)
android/reflex-host/             Kotlin ReflexHost (debugImplementation only in the app)
android/core/                    Android Core, pure JVM: foundation/, app/, introspection/, features/<feature>/
android/cli/                     headless Android host (fat jar): Core + fixture adapters over stdio
android/app/                     Compose app; src/debug = socket server, src/release = no-op
fixtures/<Domain>/<scenario>.json  fixtures shared by both platforms
tests/sdk/                       Bun test SDK + the platform-agnostic CLI (no runtime dependencies)
tests/behavior/                  headless behavior tests (the bulk of coverage)
tests/reflex-live/               a few journeys against real DEBUG builds
tools/                           check-architecture.ts (lint), toolchain-gate.sh (SessionStart hook)
```

## Non-negotiable rules

1. **Share the test, not the code.** No shared code or cross-platform layer between Swift and Kotlin. Every behavior
   is implemented twice, natively, and tested once.
2. **Core has no UI and no device.** Nothing in `AppCore` / `android/core` imports UIKit, SwiftUI, AppKit, Security,
   LocalAuthentication, `android.*` or `androidx.*`, or ReflexHost. If Core doesn't build on macOS / the plain JVM,
   it isn't Core. Everything Core needs from the outside is a protocol in `Foundation/Platform.swift` /
   `foundation/Platform.kt`, passed to `Core(...)`.
3. **The ViewModel contract.** State (everything the screen shows) · Action (everything a user can do) · Message
   (`viewAction(Action)` + service results) · `update(state, message)`: **the only place state changes**. Business
   rules live only in `update`. Async results come back as Messages. Services are injected protocols.
4. **Views are dumb.** A view reads `state` and calls `send(action:)`. Nothing else: no business rules
   (expose `canX`/derived fields in State instead), and no calls to `update` or `send(message:)`.
5. **Navigation lives in Core.** Screens change by mutating a navigator (`StackNavigator`), never by a view pushing
   something. Deep links are `AppRoot.open(url)`. The UI only reflects `routes` and routes system back to `goBack()`.
6. **Node rules.** (1) If a user can see it, it's in `state`; if a user can do it, it's in `actions`. (2) A node
   never contains a conditional and lists every action every time. (3) Actions go through `send(action:)` only.
   The app never calls introspection; delete a node file and the app still builds.
7. **Grey-box, zero network, no mocks.** Tests see only name/state/actions. Everything underneath is real; only the
   edges are swapped, by passing different fixtures. Any network attempt or fixture miss fails the test. No
   mocking frameworks, no sleeps, no wait-for-element helpers (the SDK polls).
8. **Parity is enforced.** A change is not done until the same behavior test file passes unchanged on both
   platforms, and `./reflex check` passes. Never weaken a test to make one platform pass.
9. **User-visible strings that tests assert on** (errors, labels in state) are literals in the ViewModel, identical
   on both platforms, never derived from platform error descriptions.

## Naming

| Thing | Convention | Example |
|---|---|---|
| Screen | `<Name>ViewModel` in `Features/<Feature>/` (Swift) / `features/<feature>/` (Kotlin) | `SampleDetailViewModel` |
| Node name | ViewModel name without `ViewModel`, PascalCase | `"SampleDetail"` |
| Own action title | Action case in PascalCase | `.setCount` → `"SetCount"` |
| Lent action title | camelCase, only containers lend | `goBack`, `selectTab`, `dismiss` |
| Node file | `<VM>+Introspection.swift` / `<VM>IntrospectionNode.kt` (+ a line in `IntrospectionRegistrations.kt`) | |
| Action input | `struct XInput: IntrospectionInput` / `@Serializable data class XInput`; required `T` or optional `T?`, no defaults | `SetCountInput(count)` |
| GraphQL operation | same `operationName` on both platforms; fixtures match on it | `"SampleSave"` |
| Fixture | `fixtures/<Domain>/<camelCaseScenario>.json` → `fixture("Domain.scenario")` | `Sample.saveFails` |
| Behavior test | `tests/behavior/<feature>.cli-test.ts`, names `given <precondition>, <what the user can do/sees>` | |

## Decisions (where this repo fills gaps in the original architecture)

Keep this list current: if you change one of these, update it in the same change.

- Protocol: NDJSON v1 as implemented by `ReflexHost.swift` / `ReflexHost.kt` (commands `session.create`, `ls`, `do`,
  `tree`, `session.close`; codes `ACTION_NOT_AVAILABLE`, `INVALID_PAYLOAD`, `UNKNOWN_SESSION`, `FIXTURE_NOT_FOUND`,
  `INVALID_FIXTURE`, `BAD_REQUEST`, `UNSUPPORTED`, `INTERNAL`; events `ready`, `network_violation`,
  `fixture_miss`, `log`). One host process per platform per run; one session (fresh Core) per test.
- JSON output is byte-identical across platforms: compact, integral numbers without `.0`, keys in declaration order.
  Swift uses an ordered `JSONObject` and its own parser for this.
- Input schemas are inferred, never hand-written: Swift from `Decodable` (a recording decoder, so no macro and no
  swift-syntax dependency), Kotlin from the kotlinx `SerialDescriptor`. Payloads are validated before reaching the ViewModel.
- Swift ViewModel is a protocol with associated types (a class can't use its own nested types as superclass
  generics). Kotlin's base class names `send(message)` `@JvmName("sendMessage")` to avoid a JVM signature clash.
- Kotlin ViewModels run on one thread (Main in the app, a single `core` thread in the CLI); `update` may launch side
  effects; use `attempt { }` (not `runCatching`) around service calls.
- The human CLI is one TypeScript tool (`tests/sdk/cli.ts`) shared by both platforms; hosts only speak NDJSON.
- `precondition(name, expectation?)` takes an optional expectation (wait for initial-load state without sleeps).
- Fixture merge: a later fixture's entries for operation X replace *all* earlier entries for X. Entries for one
  operation are consumed in order; the last repeats. Unknown top-level keys are rejected.
- Navigation containers: only `StackNavigator` exists so far. Tab/Modal contracts are specified in
  `reflex-new-screen` and are added (on both platforms) when the product needs them.
- Apps: iOS project from XcodeGen; Android `:app` is included only when an Android SDK is present
  (`REFLEX_SKIP_APP=1` skips it). Debug socket server: iOS `#if DEBUG`, Android `src/debug` + `debugImplementation`.
- The `Sample` feature is a reference implementation (see below).

## The Sample feature (reference implementation)

`Sample` (list → detail with a counter, save, errors, deep link) demonstrates every part of the architecture and is
what the skills point to as the canonical example. **When the first real feature lands, delete it** (the
`reflex-feature` skill lists every file) and make the real first screen the root route in `AppRoot`.

## Placeholders to set when the product is decided

Bundle id / applicationId `app.reflex.mobile` (`ios/AppMobile/project.yml`, `android/app/build.gradle.kts`), app
name, GraphQL endpoint (`AppConfig.graphQLEndpoint`, `BuildConfig.GRAPHQL_ENDPOINT`), deep link scheme (routing in
`AppRoot.open` is scheme-agnostic), real persistence for secrets on Android (Keystore), remote feature flags.
Kotlin packages (`app.reflex.*`) don't need to change.
