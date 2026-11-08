package app.reflex.introspection

/**
 * Focus and lent-action resolution, identical to iOS `Resolution.resolve`.
 *
 * - The focused node is the deepest node in the `child` chain (what the user is looking at).
 * - Available actions = the focused node's own actions, then actions lent by ancestors
 *   (nearest ancestor first). On a title collision the child wins.
 */
class Resolution private constructor(val chain: List<IntrospectionNode>, val available: List<Available>) {
    data class Available(val action: IntrospectionAction, val lentBy: String?)

    val focused: IntrospectionNode get() = chain.last()
    val path: List<String> get() = chain.map { it.name }

    fun action(title: String): Available? = available.firstOrNull { it.action.title == title }

    /** `{"name", "path", "state", "actions": [{"title", "input"?, "lentBy"?}]}` */
    fun snapshot(): JsonValue = JsonValue.obj(
        "name" to JsonValue.Str(focused.name),
        "path" to JsonValue.Arr(path.map(JsonValue::Str)),
        "state" to JsonValue.Obj(focused.state),
        "actions" to JsonValue.Arr(available.map { describe(it.action, it.lentBy) }),
    )

    /** The whole chain from root to focus, for debugging (`tree`). */
    fun tree(): JsonValue = JsonValue.Arr(chain.map { node ->
        JsonValue.obj(
            "name" to JsonValue.Str(node.name),
            "state" to JsonValue.Obj(node.state),
            "actions" to JsonValue.Arr(node.actions.map { describe(it, null, scope = true) }),
        )
    })

    companion object {
        fun resolve(root: IntrospectionNode): Resolution {
            val chain = generateSequence(root) { it.child }.toList()
            val available = chain.last().actions.filter { it.scope == IntrospectionAction.Scope.LOCAL }
                .map { Available(it, null) }.toMutableList()
            for (ancestor in chain.dropLast(1).asReversed()) {
                for (action in ancestor.actions) {
                    if (action.scope == IntrospectionAction.Scope.LENT && available.none { it.action.title == action.title }) {
                        available += Available(action, ancestor.name)
                    }
                }
            }
            return Resolution(chain, available)
        }

        private fun describe(action: IntrospectionAction, lentBy: String?, scope: Boolean = false): JsonValue {
            val entries = linkedMapOf<String, JsonValue>("title" to JsonValue.Str(action.title))
            action.inputSchema?.let { entries["input"] = it }
            lentBy?.let { entries["lentBy"] = JsonValue.Str(it) }
            if (scope && action.scope == IntrospectionAction.Scope.LENT) entries["scope"] = JsonValue.Str("lent")
            return JsonValue.Obj(entries)
        }
    }
}
