package app.reflex.introspection

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind

/**
 * Derives an input's JSON Schema from its kotlinx `SerialDescriptor`, so nobody maintains schemas by hand.
 * Produces exactly what iOS `SchemaInference` produces for the equivalent Swift struct.
 *
 * Field rules (keep both platforms identical): required fields are non-null with no default;
 * optional fields are nullable (`T?`, no default) → not required, `"type": ["T", "null"]`.
 */
@OptIn(ExperimentalSerializationApi::class)
object SchemaInference {
    fun schema(descriptor: SerialDescriptor): JsonValue {
        val base = when (val kind = descriptor.kind) {
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> type("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> type("string")
            PrimitiveKind.BOOLEAN -> type("boolean")
            SerialKind.ENUM -> JsonValue.obj(
                "type" to JsonValue.Str("string"),
                "enum" to JsonValue.Arr((0 until descriptor.elementsCount).map { JsonValue.Str(descriptor.getElementName(it)) }),
            )
            StructureKind.LIST -> JsonValue.obj("type" to JsonValue.Str("array"), "items" to schema(descriptor.getElementDescriptor(0)))
            StructureKind.CLASS, StructureKind.OBJECT -> {
                val properties = LinkedHashMap<String, JsonValue>()
                val required = mutableListOf<JsonValue>()
                for (i in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(i)
                    val element = descriptor.getElementDescriptor(i)
                    properties[name] = schema(element)
                    if (!descriptor.isElementOptional(i) && !element.isNullable) required += JsonValue.Str(name)
                }
                JsonValue.obj(
                    "type" to JsonValue.Str("object"),
                    "properties" to JsonValue.Obj(properties),
                    "required" to JsonValue.Arr(required),
                    "additionalProperties" to JsonValue.Bool(false),
                )
            }
            else -> error("Cannot infer a JSON schema for ${descriptor.serialName} (kind $kind)")
        }
        return if (descriptor.isNullable) nullable(base) else base
    }

    private fun type(name: String): JsonValue = JsonValue.obj("type" to JsonValue.Str(name))

    private fun nullable(schema: JsonValue): JsonValue {
        val entries = LinkedHashMap(schema.objectValue ?: return schema)
        entries["type"] = when (val t = entries["type"]) {
            is JsonValue.Str -> JsonValue.Arr(listOf(t, JsonValue.Str("null")))
            is JsonValue.Arr -> JsonValue.Arr(t.items + JsonValue.Str("null"))
            else -> return schema
        }
        return JsonValue.Obj(entries)
    }
}

/** Same algorithm and messages as iOS `SchemaValidator`. */
object SchemaValidator {
    /** Returns the first violation, or null when the value conforms. */
    fun validate(value: JsonValue, schema: JsonValue): String? = validate(value, schema, "")

    private fun validate(value: JsonValue, schema: JsonValue, path: String): String? {
        val pointer = path.ifEmpty { "/" }
        schema["type"]?.let { types ->
            val allowed = types.arrayValue?.mapNotNull { it.stringValue } ?: listOfNotNull(types.stringValue)
            if (allowed.none { matches(value, it) }) return "$pointer must be ${allowed.joinToString(" or ")}"
        }
        schema["enum"]?.arrayValue?.let { options ->
            if (value !in options) return "$pointer must be one of ${options.joinToString(", ") { it.serialize() }}"
        }
        if (value is JsonValue.Obj) {
            val properties = schema["properties"]?.objectValue ?: emptyMap()
            for (key in schema["required"]?.arrayValue.orEmpty().mapNotNull { it.stringValue }) {
                if (key !in value.entries) return "$path/$key is required"
            }
            if (schema["additionalProperties"] == JsonValue.Bool(false)) {
                for (key in value.entries.keys) if (key !in properties) return "$path/$key is not allowed"
            }
            for ((key, propertySchema) in properties) {
                val child = value.entries[key] ?: continue
                validate(child, propertySchema, "$path/$key")?.let { return it }
            }
        }
        if (value is JsonValue.Arr) {
            schema["items"]?.let { itemSchema ->
                value.items.forEachIndexed { i, item -> validate(item, itemSchema, "$path/$i")?.let { return it } }
            }
        }
        return null
    }

    private fun matches(value: JsonValue, type: String): Boolean = when (type) {
        "string" -> value is JsonValue.Str
        "boolean" -> value is JsonValue.Bool
        "object" -> value is JsonValue.Obj
        "array" -> value is JsonValue.Arr
        "null" -> value == JsonValue.Null
        "number" -> value is JsonValue.Num
        "integer" -> value is JsonValue.Num && value.value.isFinite() && value.value == Math.rint(value.value)
        else -> false
    }
}
