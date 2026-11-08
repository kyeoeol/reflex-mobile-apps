package app.reflex.introspection

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * The JSON view of one object in the app's tree (a ViewModel, a navigator, the app root).
 *
 * Node rules (enforced by `./reflex check`):
 * 1. If a user can see it, it's in [state]. If a user can do it, it's in [actions].
 * 2. Nodes never repeat UI logic: every action is listed every time, with no conditionals.
 *    If you need an `if`, the rule belongs in the ViewModel's `update` and the result in `state`.
 * 3. Actions go through `send(action)` and nothing else.
 */
interface IntrospectionNode {
    /** Stable screen identifier: the ViewModel name without the `ViewModel` suffix, PascalCase. */
    val name: String
    /** Every field a user could see, in display order (use `linkedMapOf`). */
    val state: Map<String, JsonValue>
    /** One per Action case (PascalCase titles). Containers add lent actions (camelCase titles). */
    val actions: List<IntrospectionAction>
    /** The node below this one; followed from the root to find the focused node. */
    val child: IntrospectionNode?
}

class InvalidPayload(message: String) : Exception(message)

class IntrospectionAction internal constructor(
    val title: String,
    val scope: Scope,
    val inputSchema: JsonValue?,
    private val body: (JsonValue?) -> Unit,
) {
    enum class Scope {
        /** The node's own action. */
        LOCAL,
        /** Deliberately lent by a container to whatever child is focused (e.g. `goBack`). */
        LENT,
    }

    constructor(title: String, scope: Scope = Scope.LOCAL, body: () -> Unit) : this(title, scope, null, { payload ->
        if (payload != null && payload != JsonValue.Null) throw InvalidPayload("$title takes no input")
        body()
    })

    /** Validates the payload (throws [InvalidPayload] before anything reaches the ViewModel), then runs. */
    fun perform(payload: JsonValue?) = body(payload)
}

private val inputJson = Json { explicitNulls = false }

/**
 * An action that takes an input. The schema comes from the serializer's descriptor:
 *
 *     @Serializable data class SetCountInput(val count: Int)
 *     IntrospectionAction("SetCount", SetCountInput.serializer()) { viewModel.send(Action.SetCount(it.count)) }
 */
fun <I> IntrospectionAction(
    title: String,
    input: KSerializer<I>,
    scope: IntrospectionAction.Scope = IntrospectionAction.Scope.LOCAL,
    body: (I) -> Unit,
): IntrospectionAction {
    val schema = SchemaCache.schema(input)
    return IntrospectionAction(title, scope, schema) { payload ->
        val value = if (payload == null || payload == JsonValue.Null) JsonValue.Obj(emptyMap()) else payload
        SchemaValidator.validate(value, schema)?.let { throw InvalidPayload(it) }
        val decoded = try {
            inputJson.decodeFromString(input, value.serialize())
        } catch (e: Exception) {
            throw InvalidPayload(e.message ?: "invalid payload")
        }
        body(decoded)
    }
}

private object SchemaCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, JsonValue>()
    fun schema(serializer: KSerializer<*>): JsonValue =
        cache.getOrPut(serializer.descriptor.serialName) { SchemaInference.schema(serializer.descriptor) }
}

/**
 * ViewModel → node lookup. Kotlin can't add an interface to a type from another file, so each node is a
 * separate class registered here (see core's `IntrospectionRegistrations.kt`). App code never calls this.
 */
object IntrospectionRegistry {
    private val factories = java.util.concurrent.ConcurrentHashMap<KClass<*>, (Any) -> IntrospectionNode>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> register(type: KClass<T>, factory: (T) -> IntrospectionNode) {
        factories[type] = { factory(it as T) }
    }

    fun nodeFor(viewModel: Any): IntrospectionNode? = factories[viewModel::class]?.invoke(viewModel)
}
