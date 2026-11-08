---
name: reflex-feature
description: The end-to-end workflow for building or changing ANY app feature, screen, flow, or behavior in this repo (iOS + Android, Reflex architecture), including the very first real feature once the product is decided. Use it whenever the request is to implement, add, change, or fix app behavior, even if it sounds like a one-platform or UI-only change.
---

# Building a feature the Reflex way

A feature is one behavior, implemented twice natively (Swift + Kotlin) and specified once by a shared behavior test.
Follow these checkpoints in order. Each has a gate; don't move on until it passes.

## 0. Toolchain gate

If `.reflex/toolchain.local.json` is missing, run `./reflex setup` first (see CLAUDE.md).

## 1. Write the contract first (no code yet)

Write down, for every screen the feature touches, the node it will expose. This is the shared spec both
platforms implement, and the vocabulary the tests use:

```
SampleDetail                                  ← node name = ViewModel name without "ViewModel"
  state   itemId: string, title: string, count: int, canDecrement: bool, isSaved: bool, saveError: string|null
  actions Increment, Decrement, SetCount {count: int}, Save        (+ goBack lent by StackNavigator)
  rules   count never < 0 · editing clears isSaved/saveError · Save ignored while saving
  edges   GraphQL SampleItem(id) · SampleSave(id, count)
  routes  pushed from SampleList.SelectItem · deep link app://sample/<id>
```

- State is *everything a user could see*, including loading/error states and derived flags (`canDecrement`) that
  views would otherwise compute.
- Every user interaction is an Action. Every external input (network, storage, device, flags) is an edge.
- New edge (a GraphQL operation or a platform capability)? → `reflex-add-edge`.

Gate: the contract covers every journey in the request.

## 2. Fixtures

Create `fixtures/<Domain>/<scenario>.json` for the happy path and one fixture per alternative setup (failure,
empty, flag on…), usually via `extends`. → `reflex-add-fixture`.

## 3. Behavior test (before the implementation)

Write `tests/behavior/<feature>.cli-test.ts`: one test per journey, split only where the setup (fixtures) differs.
→ `reflex-write-behavior-test`. It fails now; that's expected.

## 4. iOS core

Implement the ViewModels, nodes, routes, and services in `ios/Packages/AppCore`. → `reflex-new-screen` (and
`reflex-add-edge` for new operations/capabilities, including the fixture adapter in `ios/AppCLI`).

Gate: `REFLEX_PLATFORMS=ios ./reflex test tests/behavior/<feature>.cli-test.ts` passes.
Use `./reflex cli ios --fixture <Domain.scenario>` (`reflex-explore`) to look around while building.

## 5. Android core: same test, unchanged

Implement the same contract in `android/core` (node class + registration in `IntrospectionRegistrations.kt`).

Gate: `REFLEX_PLATFORMS=android ./reflex test tests/behavior/<feature>.cli-test.ts` passes **without editing the
test**. If it fails only here → `reflex-parity`. Never change the test to fit one platform; change the test only if
it was wrong for both, then re-run both.

## 6. Discipline gate

`./reflex check`: fix every finding (it checks core purity, unconditional nodes, the single writer, node coverage,
iOS/Android node + operation parity, fixtures, and test hygiene).

## 7. Views

Add the SwiftUI view (`ios/AppMobile/AppMobile/Features/<Feature>/`) and the Compose screen
(`android/app/src/main/kotlin/app/reflex/mobile/features/<feature>/`), plus their `RouteView` / `RootScreen` cases.
Views read state and call `send(action:)`. Nothing else. → `reflex-new-screen` §Views.

Gate: `./reflex app ios` and `./reflex app android` build. Behavior tests don't cover UI wiring; check it by
running the app (or, with a DEBUG build running, `./reflex cli <platform> --live`).

## 8. Done

`./reflex ci` is green (lint + unit + the full behavior suite on both platforms). Report what was added per
platform and the test names. If you changed any item in CLAUDE.md's "Decisions" list, update it.

## The first real feature: remove the Sample reference

When the first real feature lands, delete Sample everywhere and make the new first screen the root route:

- `ios/Packages/AppCore/Sources/AppCore/Features/Sample/`
- `android/core/src/main/kotlin/app/reflex/core/features/sample/`
- `ios/AppMobile/AppMobile/Features/Sample/`, `android/app/src/main/kotlin/app/reflex/mobile/features/sample/`
- `fixtures/Sample/`, `tests/behavior/sample.cli-test.ts`, `tests/reflex-live/sample-live.cli-test.ts` (replace
  with a live journey for the new feature)
- The Sample cases in `Route` (Swift `App/AppRoot.swift`, Kotlin `app/AppRoot.kt`), `AppRoot`'s factory,
  `SampleNavigating` conformance and the `sample` deep link; `RouteView` (iOS `RootView.swift`), `RootScreen`
  (Android `MainActivity.kt`); the registrations in `IntrospectionRegistrations.kt`.
- Then `./reflex ci`. Also revisit CLAUDE.md's "Placeholders" section with the user.

## Anti-patterns (stop and restructure if you catch yourself)

- Writing iOS and Android in one go without the iOS gate in between: parity bugs become hard to locate.
- An `if` in a node, a rule in a view, or `state.x =` outside `update`.
- Making a platform API call from Core (URLSession, Keychain, SharedPreferences…) instead of adding an edge.
- Sleeping or waiting in a test; mocking a service; letting a test reach the network.
- Declaring done with only one platform verified.
