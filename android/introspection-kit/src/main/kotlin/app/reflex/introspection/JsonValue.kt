package app.reflex.introspection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * The JSON model shared by nodes, the protocol, and fixtures.
 *
 * Serialization rules (identical to iOS `JSONValue.serialized()`, so both platforms emit the same bytes):
 * integral numbers have no decimal point (`13`, never `13.0`); object keys keep insertion order; output is compact.
 */
sealed interface JsonValue {
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue {
        constructor(value: Int) : this(value.toDouble())
    }
    data class Bool(val value: Boolean) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    /** Insertion-ordered. Equality is order-insensitive, like JSON. */
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue
    data object Null : JsonValue

    fun serialize(): String = StringBuilder().also { write(this, it) }.toString()

    val stringValue: String? get() = (this as? Str)?.value
    val numberValue: Double? get() = (this as? Num)?.value
    val boolValue: Boolean? get() = (this as? Bool)?.value
    val arrayValue: List<JsonValue>? get() = (this as? Arr)?.items
    val objectValue: Map<String, JsonValue>? get() = (this as? Obj)?.entries
    operator fun get(key: String): JsonValue? = objectValue?.get(key)

    companion object {
        /** Conversions that keep conditionals out of node files: `JsonValue.of(state.saveError)`. */
        fun of(value: String?): JsonValue = value?.let(::Str) ?: Null
        fun of(value: Int?): JsonValue = value?.let(::Num) ?: Null
        fun of(value: Double?): JsonValue = value?.let(::Num) ?: Null
        fun of(value: Boolean?): JsonValue = value?.let(::Bool) ?: Null
        fun of(values: List<JsonValue>): JsonValue = Arr(values)
        fun obj(vararg entries: Pair<String, JsonValue>): JsonValue = Obj(linkedMapOf(*entries))

        fun parse(text: String): JsonValue = fromElement(Json.parseToJsonElement(text))

        fun fromElement(element: JsonElement): JsonValue = when (element) {
            is JsonNull -> Null
            is JsonPrimitive -> when {
                element.isString -> Str(element.content)
                element.content == "true" -> Bool(true)
                element.content == "false" -> Bool(false)
                else -> Num(element.content.toDouble())
            }
            is JsonArray -> Arr(element.map(::fromElement))
            is JsonObject -> Obj(element.entries.associateTo(LinkedHashMap()) { it.key to fromElement(it.value) })
        }

        fun formatNumber(n: Double): String = when {
            !n.isFinite() -> "null"
            n == Math.rint(n) && abs(n) < 1e15 -> n.toLong().toString()
            else -> n.toString()
        }

        private fun write(value: JsonValue, out: StringBuilder) {
            when (value) {
                is Str -> writeString(value.value, out)
                is Num -> out.append(formatNumber(value.value))
                is Bool -> out.append(if (value.value) "true" else "false")
                Null -> out.append("null")
                is Arr -> {
                    out.append('[')
                    value.items.forEachIndexed { i, item ->
                        if (i > 0) out.append(',')
                        write(item, out)
                    }
                    out.append(']')
                }
                is Obj -> {
                    out.append('{')
                    value.entries.entries.forEachIndexed { i, (key, item) ->
                        if (i > 0) out.append(',')
                        writeString(key, out)
                        out.append(':')
                        write(item, out)
                    }
                    out.append('}')
                }
            }
        }

        private fun writeString(s: String, out: StringBuilder) {
            out.append('"')
            for (c in s) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c == '\n' -> out.append("\\n")
                    c == '\r' -> out.append("\\r")
                    c == '\t' -> out.append("\\t")
                    c == '\b' -> out.append("\\b")
                    c == '\u000C' -> out.append("\\f")
                    c.code < 0x20 -> out.append("\\u%04x".format(c.code))
                    else -> out.append(c)
                }
            }
            out.append('"')
        }
    }
}
