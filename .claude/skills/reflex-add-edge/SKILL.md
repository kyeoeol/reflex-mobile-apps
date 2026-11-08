---
name: reflex-add-edge
description: How to connect the Reflex core to anything outside the process. Covers new GraphQL operations and new platform capabilities (storage, device features, feature flags, push, camera, location, clock, files…), done as a Core protocol plus a real Mobile implementation plus fixture adapters on both CLIs. Use whenever Core needs data or a capability from the network, the OS, or the device.
---

# Adding an edge

Core never touches the outside world directly. Everything that comes from outside the process is an **edge**: a
protocol that Core declares and receives through `Core(...)`. The app passes a real implementation; the CLI passes a
fixture-backed one. Edge cases are then simulated by passing different fixtures, never by mocking.

## A. New GraphQL operation (most common)

The network edge (`GraphQLTransport`) already exists. Only add the operation, identically on both platforms:

1. **Swift**, in the feature's service file (see `Features/Sample/SampleService.swift`):
   ```swift
   struct ThingQuery: GraphQLOperation {
       static let operationName = "Thing"                 // fixtures match on this; same string on Android
       static let document = "query Thing($id: ID!) { thing(id: $id) { id name } }"
       struct Variables: Encodable, Sendable { let id: String }
       struct ResponseData: Decodable, Sendable { let thing: Thing }
       let variables: Variables
   }
   // in the real service: try await client.execute(ThingQuery(variables: .init(id: id))).thing
   ```
   Operations without variables omit `Variables`/`variables` (they default to `NoVariables`).
2. **Kotlin** (see `features/sample/SampleService.kt`):
   ```kotlin
   @Serializable data class ThingVariables(val id: String)
   @Serializable data class ThingData(val thing: Thing)
   fun thing(id: String) = GraphQLOperation("Thing", "query Thing(\$id: ID!) { thing(id: \$id) { id name } }",
       ThingVariables(id), ThingVariables.serializer(), ThingData.serializer())
   ```
   Variables must serialize to the same JSON keys on both platforms (fixtures match `variables` partially).
3. **Fixtures**: add a `graphql` entry for every scenario that reaches the operation (`reflex-add-fixture`).
4. `./reflex check` verifies the operation exists on both platforms and every fixture operation exists in Core.

Service errors: map them to a Message (e.g. `.saveFailed`) and a literal user-facing string in State. Don't expose
platform error descriptions.

## B. New platform capability

Example: "Core needs to know whether notifications are authorized". Four places, both platforms:

| Step | iOS | Android |
|---|---|---|
| 1. Protocol in Core (no platform imports) | `AppCore/Foundation/Platform.swift` | `core/…/foundation/Platform.kt` |
| 2. Pass it through `Core(...)` into `Services` | `App/Core.swift` | `app/AppRoot.kt` (`Core`, `Services`) |
| 3. Real implementation in the app | `AppMobile/Platform/PlatformImplementations.swift` | `app/…/platform/PlatformImplementations.kt` |
| 4. Fixture adapter in the CLI | `ios/AppCLI/Sources/AppCLI/FixtureAdapters.swift` + `makeSession` in `main.swift` | `android/cli/…/Main.kt` (`makeSession` + adapter class) |

Fixture keys: read values from an existing fixture section, with the same key names and defaults on both
platforms:

- device capabilities → `"device": {"notificationsAuthorized": true}` (missing key → `false`)
- feature flags → `"flags": {"thing.newFlow": true}` (missing → disabled)
- persisted values → `"storage": {"session.token": "…"}` (strings only)

Only if the capability needs data that fits none of these (e.g. a sequence of camera results) add a new top-level
fixture key, which means changing `FixtureSet` on **both** hosts (`ReflexHost/Fixtures.swift`,
`reflex-host/…/Fixtures.kt`), the allowed-keys list in both, and `tools/check-architecture.ts`. Record that in
CLAUDE.md's Decisions.

Rules:
- The protocol speaks the domain's language (`notificationsAuthorized: Bool`), not the platform's
  (`UNAuthorizationStatus`).
- Anything time- or randomness-dependent is an edge too (a `Clock` protocol), so tests stay deterministic.
- Mutating edges (requesting a permission, scanning) return their result asynchronously → a Message → `update`.
- Pick the protocol shape by cost. A cheap synchronous read (a flag, a capability, a stored value) is a
  `@MainActor protocol X: AnyObject` like `FeatureFlags` (Kotlin: plain `fun`). Anything that waits on I/O, the OS,
  or the user is a `Sendable` protocol with `async` methods like `Storage` or `GraphQLTransport` (Kotlin:
  `suspend fun`), and the real implementation leaves the main actor (Swift `@concurrent`, Kotlin
  `withContext(Dispatchers.IO)`); `update` starts it in `Task { [edge] in … self.send(message: …) }` like a service call.
- Never `#if os(...)` / `Build.VERSION` inside Core.
