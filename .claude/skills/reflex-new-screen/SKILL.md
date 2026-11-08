---
name: reflex-new-screen
description: Templates and checklist for adding a screen in this Reflex repo on both platforms. Covers ViewModel (State/Action/Message/update), introspection node, Kotlin registry line, Route case, AppRoot factory and navigation, SwiftUI and Compose views, and container navigators (tab/modal). Use when adding or reshaping a screen, ViewModel, node, route, or view.
---

# Adding a screen (both platforms)

The canonical, working example is the Sample feature. Copy its shape exactly:

| Piece | iOS | Android |
|---|---|---|
| ViewModel | `ios/Packages/AppCore/Sources/AppCore/Features/Sample/SampleDetailViewModel.swift` | `android/core/src/main/kotlin/app/reflex/core/features/sample/SampleDetailViewModel.kt` |
| Node | `…/Features/Sample/SampleDetailViewModel+Introspection.swift` | `…/features/sample/SampleDetailViewModelIntrospectionNode.kt` |
| Registration | (dynamic cast, nothing to do) | `…/core/introspection/IntrospectionRegistrations.kt` |
| Service + operations | `…/Features/Sample/SampleService.swift` | `…/features/sample/SampleService.kt` |
| Route + factory + deep link | `…/App/AppRoot.swift` | `…/core/app/AppRoot.kt` |
| View | `ios/AppMobile/AppMobile/Features/Sample/SampleViews.swift` + `RootView.swift` (`RouteView`) | `android/app/…/features/sample/SampleScreens.kt` + `MainActivity.kt` (`RootScreen`) |

Full copy-paste templates with placeholders: [templates.md](templates.md).

## Checklist

1. **ViewModel** (both): `State` = everything visible (incl. loading/error and derived flags like `canSubmit`);
   `Action` = every user interaction; `Message` = `viewAction(Action)` + one case per async result;
   `update(state, message)` is the only writer. Async work: Swift `Task { … self.send(message: …) }`, Kotlin
   `scope.launch { attempt { … }.fold(…) }`. Initial loading is triggered from `init` via `send(message: .load)`,
   never by a view's `onAppear` (the headless CLI has no views).
2. **Node** (both): `name` = VM name without `ViewModel`; `state` lists every State field in the same order on both
   platforms (Swift `JSONObject` literal / Kotlin `linkedMapOf`); one action per Action case, PascalCase titles,
   same order on both. Optionals: Swift `JSONValue(x)`, Kotlin `JsonValue.of(x)`. Inputs: Swift
   `struct XInput: IntrospectionInput`, Kotlin `@Serializable data class XInput`, with identical field names/types.
   No `if`/`switch`/`when`/`??`/`?:` in node files.
3. **Kotlin registration**: one `IntrospectionRegistry.register(...)` line in `IntrospectionRegistrations.kt`.
4. **Navigation**: add a `Route` case on both platforms. If another screen opens this one, give the opener a small
   `XNavigating` protocol/interface (Swift: `AnyObject`, held `weak`), implemented by `AppRoot`, whose method builds
   the new ViewModel with its services and pushes it. Deep link? Extend `AppRoot.open` identically on both.
5. **Services**: a protocol/interface in the feature folder, a real `GraphQL…Service` using `GraphQLClient`. New
   operation or capability → `reflex-add-edge`.
6. **Views** (§ below), plus the `RouteView` / `RootScreen` case.
7. `./reflex check` and the behavior test on both platforms.

## State and node field types

| Kind | Swift State | Swift node | Kotlin State | Kotlin node |
|---|---|---|---|---|
| String | `var title = ""` | `.string(s.title)` | `val title: String = ""` | `JsonValue.Str(it.title)` |
| Int | `var count = 0` | `.number(s.count)` | `val count: Int = 0` | `JsonValue.Num(it.count)` |
| Bool | `var isSaved = false` | `.bool(s.isSaved)` | `val isSaved: Boolean = false` | `JsonValue.Bool(it.isSaved)` |
| Optional | `var error: String?` | `JSONValue(s.error)` | `val error: String? = null` | `JsonValue.of(it.error)` |
| Derived | `var canX: Bool { … }` | `.bool(s.canX)` | `val canX: Boolean get() = …` | `JsonValue.Bool(it.canX)` |
| List of objects | `var items: [Item]` | `.array(s.items.map { ["id": .string($0.id)] })` | `val items: List<Item>` | `JsonValue.Arr(it.items.map { i -> JsonValue.obj("id" to JsonValue.Str(i.id)) })` |

Dates, money and enums: expose them as the string/number the user sees or a stable code, identically formatted on
both platforms (format in the ViewModel, not in the node).

## Views

- iOS: `struct XView: View { let viewModel: XViewModel; var body: some View { let state = viewModel.state; … } }`.
  Buttons call `viewModel.send(action: …)`; enabled/disabled comes from state (`.disabled(!state.canX)`).
- Android: `@Composable fun XScreen(viewModel: XViewModel)` with
  `val state by viewModel.state.collectAsStateWithLifecycle()`; `onClick = { viewModel.send(Action.X) }`.
- No business rules, no service calls, no navigation decisions in views. Back navigation calls the navigator.
- Never import IntrospectionKit/ReflexHost in app code outside `Debug/` (iOS) or `src/debug/` (Android).

## Container navigators (when the product needs them)

Only `StackNavigator` exists today (`Foundation/StackNavigator.swift`, `foundation/StackNavigator.kt`, nodes in
`Foundation/Introspection/` and `core/introspection/`). Add these to Foundation on **both** platforms with exactly
these node contracts, then wire them into `AppRoot`'s tree:

| Container | Node name | state | Lent action | child |
|---|---|---|---|---|
| Stack | `StackNavigator` | `{"routes": [<node names>]}` | `goBack` (no-op at root) | top route's node |
| Tab | `TabNavigator` | `{"tabs": [<tab ids>], "selectedTab": <tab id>}` | `selectTab {tab: string}` (unknown id: no-op) | selected tab's stack node |
| Modal | `ModalNavigator` | `{"presented": <node name> \| null}` | `dismiss` (no-op when nothing is presented) | presented node, else the underlying content node |

Containers lend actions with `scope: .lent` / `Scope.LENT`; plain ViewModels never lend. Containers live in
Foundation, not Features, because they legitimately branch on optional children.
