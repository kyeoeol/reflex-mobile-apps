package app.reflex.introspection

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Unit tests cover primitives only. These mirror ios/Packages/IntrospectionKit/Tests byte for byte,
// which is what keeps both platforms' wire output identical.

class JsonTest {
    @Test fun serializesDeterministically() {
        val value = JsonValue.obj(
            "b" to JsonValue.Num(13), "a" to JsonValue.Num(1.5), "s" to JsonValue.Str("q\"\n"),
            "n" to JsonValue.Null, "arr" to JsonValue.Arr(listOf(JsonValue.Bool(true), JsonValue.Bool(false))),
        )
        assertEquals("""{"b":13,"a":1.5,"s":"q\"\n","n":null,"arr":[true,false]}""", value.serialize())
        assertEquals("13", JsonValue.Num(13.0).serialize())
        assertEquals("0", JsonValue.Num(-0.0).serialize())
    }

    @Test fun parsesPreservingKeyOrder() {
        val value = JsonValue.parse("""{"z": 1, "a": {"y": [1, 2.5, "xé"], "b": null}}""")
        assertEquals(listOf("z", "a"), value.objectValue!!.keys.toList())
        assertEquals("""{"z":1,"a":{"y":[1,2.5,"xé"],"b":null}}""", value.serialize())
    }
}

class SchemaTest {
    @Serializable enum class Sort { newest, oldest }
    @Serializable data class Nested(val name: String)
    @Serializable data class Input(val count: Int, val note: String?, val tags: List<String>, val nested: Nested, val sort: Sort)

    @Test fun infersSchemaFromDescriptor() {
        assertEquals(
            """{"type":"object","properties":{"count":{"type":"integer"},"note":{"type":["string","null"]},"tags":{"type":"array","items":{"type":"string"}},"nested":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"],"additionalProperties":false},"sort":{"type":"string","enum":["newest","oldest"]}},"required":["count","tags","nested","sort"],"additionalProperties":false}""",
            SchemaInference.schema(Input.serializer().descriptor).serialize(),
        )
    }

    @Test fun validatesPayloads() {
        val schema = SchemaInference.schema(Input.serializer().descriptor)
        val ok = JsonValue.parse("""{"count": 2, "tags": [], "nested": {"name": "x"}, "sort": "newest"}""") as JsonValue.Obj
        fun with(key: String, value: JsonValue) = JsonValue.Obj(LinkedHashMap(ok.entries).apply { put(key, value) })
        assertNull(SchemaValidator.validate(ok, schema))
        assertEquals("/count must be integer", SchemaValidator.validate(with("count", JsonValue.Str("x")), schema))
        assertEquals("/count must be integer", SchemaValidator.validate(with("count", JsonValue.Num(2.5)), schema))
        assertEquals("/nested/name must be string", SchemaValidator.validate(with("nested", JsonValue.obj("name" to JsonValue.Num(1))), schema))
        assertEquals("/count is required", SchemaValidator.validate(JsonValue.Obj(emptyMap()), schema))
        assertEquals("/ must be object", SchemaValidator.validate(JsonValue.Str("x"), schema))
        assertEquals("/bogus is not allowed", SchemaValidator.validate(with("bogus", JsonValue.Num(1)), schema))
        assertEquals("""/sort must be one of "newest", "oldest"""", SchemaValidator.validate(with("sort", JsonValue.Str("sideways")), schema))
    }
}

class ResolutionTest {
    private class Leaf : IntrospectionNode {
        override val name = "Leaf"
        override val state = mapOf<String, JsonValue>("x" to JsonValue.Num(1))
        override val actions = listOf(IntrospectionAction("Tap") {}, IntrospectionAction("goBack") {})
        override val child: IntrospectionNode? = null
    }

    private class Container : IntrospectionNode {
        override val name = "Stack"
        override val state = emptyMap<String, JsonValue>()
        override val actions = listOf(IntrospectionAction("goBack", IntrospectionAction.Scope.LENT) {}, IntrospectionAction("Hidden") {})
        override val child: IntrospectionNode = Leaf()
    }

    @Test fun focusesDeepestNodeAndAddsLentActions() {
        val resolution = Resolution.resolve(Container())
        assertEquals(listOf("Stack", "Leaf"), resolution.path)
        assertEquals(listOf("Tap", "goBack"), resolution.available.map { it.action.title })
        assertEquals(listOf<String?>(null, null), resolution.available.map { it.lentBy })
        assertNull(resolution.action("Hidden"))
    }
}
