# Screen templates

Replace `Thing` / `thing` / `Things` with the screen's name and `ThingService` with its service. Keep field order and
action order identical across the four files.

## Swift ViewModel: `ios/Packages/AppCore/Sources/AppCore/Features/<Feature>/ThingViewModel.swift`

```swift
import Foundation
import Observation

@MainActor
public final class ThingViewModel: ViewModel {
    @MainActor @Observable public final class State {
        public let thingId: String
        // Stored fields: `public fileprivate(set) var`, so only this file (i.e. `update`) can write them.
        public fileprivate(set) var name = ""
        public fileprivate(set) var isLoading = true
        public fileprivate(set) var loadError: String?
        public var canSubmit: Bool { !name.isEmpty && !isLoading }   // derived: no `if` in views or nodes
        init(thingId: String) { self.thingId = thingId }
    }

    public enum Action {
        case rename(String)
        case submit
    }

    public enum Message {
        case viewAction(Action)
        case load
        case loaded(Thing)
        case loadFailed
    }

    public let state: State
    private let service: any ThingService

    init(thingId: String, service: any ThingService) {
        self.state = State(thingId: thingId)
        self.service = service
        send(message: .load)                       // loading starts in Core, not in a view's onAppear
    }

    public func send(action: Action) { send(message: .viewAction(action)) }

    public func update(state: State, message: Message) {
        switch message {
        case .viewAction(.rename(let name)):
            state.name = name
        case .viewAction(.submit) where state.canSubmit:
            // start the side effect here; its result comes back as a Message
            break
        case .load:
            let id = state.thingId
            Task { [service] in
                do { self.send(message: .loaded(try await service.thing(id: id))) } catch { self.send(message: .loadFailed) }
            }
        case .loaded(let thing):
            state.name = thing.name
            state.isLoading = false
        case .loadFailed:
            state.isLoading = false
            state.loadError = "Couldn't load this thing."   // literal, identical on Android
        default:
            break                                      // rule-based no-ops
        }
    }
}
```

Helpers that write state must be local functions *inside* `update` (the lint rejects `state.x =` elsewhere).

## Swift node: `…/Features/<Feature>/ThingViewModel+Introspection.swift`

```swift
import IntrospectionKit

extension ThingViewModel: IntrospectionProviding {
    public var introspectionNode: any IntrospectionNode { Introspection(viewModel: self) }

    private struct Introspection: IntrospectionNode {
        unowned let viewModel: ThingViewModel
        let name = "Thing"

        var state: JSONObject {
            [
                "thingId": .string(viewModel.state.thingId),
                "name": .string(viewModel.state.name),
                "isLoading": .bool(viewModel.state.isLoading),
                "loadError": JSONValue(viewModel.state.loadError),
                "canSubmit": .bool(viewModel.state.canSubmit),
            ]
        }

        var actions: [IntrospectionAction] {
            [
                IntrospectionAction(title: "Rename", input: RenameInput.self) { viewModel.send(action: .rename($0.name)) },
                IntrospectionAction(title: "Submit") { viewModel.send(action: .submit) },
            ]
        }

        var child: (any IntrospectionNode)? { nil }
    }
}

struct RenameInput: IntrospectionInput {
    let name: String
}
```

## Kotlin ViewModel: `android/core/src/main/kotlin/app/reflex/core/features/<feature>/ThingViewModel.kt`

```kotlin
package app.reflex.core.features.thing

import app.reflex.core.foundation.ViewModel
import app.reflex.core.foundation.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ThingViewModel(
    thingId: String,
    private val service: ThingService,
    scope: CoroutineScope,
) : ViewModel<ThingViewModel.State, ThingViewModel.Action, ThingViewModel.Message>(State(thingId), scope) {

    data class State(
        val thingId: String,
        val name: String = "",
        val isLoading: Boolean = true,
        val loadError: String? = null,
    ) {
        val canSubmit: Boolean get() = name.isNotEmpty() && !isLoading
    }

    sealed interface Action {
        data class Rename(val name: String) : Action
        data object Submit : Action
    }

    sealed interface Message {
        data class ViewAction(val action: Action) : Message
        data object Load : Message
        data class Loaded(val thing: Thing) : Message
        data object LoadFailed : Message
    }

    init {
        send(Message.Load)
    }

    override fun wrap(action: Action) = Message.ViewAction(action)

    override fun update(state: State, message: Message): State = when (message) {
        is Message.ViewAction -> when (val action = message.action) {
            is Action.Rename -> state.copy(name = action.name)
            Action.Submit -> if (state.canSubmit) state /* launch the side effect here */ else state
        }
        Message.Load -> {
            scope.launch { attempt { service.thing(state.thingId) }.fold({ send(Message.Loaded(it)) }, { send(Message.LoadFailed) }) }
            state
        }
        is Message.Loaded -> state.copy(name = message.thing.name, isLoading = false)
        Message.LoadFailed -> state.copy(isLoading = false, loadError = "Couldn't load this thing.")
    }
}
```

## Kotlin node: `…/features/<feature>/ThingViewModelIntrospectionNode.kt`

```kotlin
package app.reflex.core.features.thing

import app.reflex.core.features.thing.ThingViewModel.Action
import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.JsonValue
import kotlinx.serialization.Serializable

class ThingViewModelIntrospectionNode(private val viewModel: ThingViewModel) : IntrospectionNode {
    override val name = "Thing"

    override val state: Map<String, JsonValue>
        get() = viewModel.state.value.let {
            linkedMapOf(
                "thingId" to JsonValue.Str(it.thingId),
                "name" to JsonValue.Str(it.name),
                "isLoading" to JsonValue.Bool(it.isLoading),
                "loadError" to JsonValue.of(it.loadError),
                "canSubmit" to JsonValue.Bool(it.canSubmit),
            )
        }

    override val actions
        get() = listOf(
            IntrospectionAction("Rename", RenameInput.serializer()) { viewModel.send(Action.Rename(it.name)) },
            IntrospectionAction("Submit") { viewModel.send(Action.Submit) },
        )

    override val child: IntrospectionNode? = null
}

@Serializable
data class RenameInput(val name: String)
```

Then in `IntrospectionRegistrations.kt`:

```kotlin
IntrospectionRegistry.register(ThingViewModel::class) { ThingViewModelIntrospectionNode(it) }
```

## Route + factory

Swift `App/AppRoot.swift`:

```swift
public enum Route: Routable, Hashable {
    case thing(ThingViewModel)
    // …
    public var viewModel: AnyObject { switch self { case .thing(let vm): vm /* … */ } }
}

// Opener's navigation protocol (in the opener's feature folder)
@MainActor public protocol ThingNavigating: AnyObject { func showThing(id: String) }

extension AppRoot: ThingNavigating {
    public func showThing(id: String) {
        navigator.push(.thing(ThingViewModel(thingId: id, service: GraphQLThingService(client: services.client))))
    }
}
```

Kotlin `app/AppRoot.kt`:

```kotlin
sealed interface Route : Routable {
    data class Thing(override val viewModel: ThingViewModel) : Route
}

interface ThingNavigating { fun showThing(id: String) }

// in AppRoot (which implements ThingNavigating)
override fun showThing(id: String) {
    navigator.push(Route.Thing(ThingViewModel(id, GraphQLThingService(services.client), services.scope)))
}
```

## Views

iOS (`ios/AppMobile/AppMobile/Features/<Feature>/ThingView.swift`, plus a `case .thing(let vm): ThingView(viewModel: vm)` in `RouteView`):

```swift
import AppCore
import SwiftUI

struct ThingView: View {
    let viewModel: ThingViewModel

    var body: some View {
        let state = viewModel.state
        Form {
            TextField("Name", text: Binding(get: { state.name }, set: { viewModel.send(action: .rename($0)) }))
            if let error = state.loadError { Text(error) }
        }
        .toolbar { Button("Submit") { viewModel.send(action: .submit) }.disabled(!state.canSubmit) }
    }
}
```

Android (`android/app/src/main/kotlin/app/reflex/mobile/features/<feature>/ThingScreen.kt`, plus an
`is Route.Thing -> ThingScreen(top.viewModel)` branch in `RootScreen`):

```kotlin
@Composable
fun ThingScreen(viewModel: ThingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column {
        TextField(value = state.name, onValueChange = { viewModel.send(ThingViewModel.Action.Rename(it)) })
        state.loadError?.let { Text(it) }
        Button(onClick = { viewModel.send(ThingViewModel.Action.Submit) }, enabled = state.canSubmit) { Text("Submit") }
    }
}
```
