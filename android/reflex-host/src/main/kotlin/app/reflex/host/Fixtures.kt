package app.reflex.host

import app.reflex.introspection.JsonValue
import java.io.File

/**
 * Fixtures live at `fixtures/<Domain>/<scenario>.json`, referenced as `"<Domain>.<scenario>"`.
 * Same format, merge and matching rules as iOS `FixtureSet` (see ios/Packages/IntrospectionKit/Sources/ReflexHost/Fixtures.swift).
 * Merge rule: if a later fixture has any entry for operation X, it replaces *all* earlier entries for X;
 * `storage`, `device` and `flags` merge key by key.
 */
class FixtureSet(
    val graphql: List<GraphQLFixture> = emptyList(),
    val storage: Map<String, JsonValue> = emptyMap(),
    val device: Map<String, JsonValue> = emptyMap(),
    val flags: Map<String, JsonValue> = emptyMap(),
) {
    fun merge(other: FixtureSet): FixtureSet {
        val overridden = other.graphql.map { it.operation }.toSet()
        return FixtureSet(
            graphql = graphql.filter { it.operation !in overridden } + other.graphql,
            storage = LinkedHashMap(storage).apply { putAll(other.storage) },
            device = LinkedHashMap(device).apply { putAll(other.device) },
            flags = LinkedHashMap(flags).apply { putAll(other.flags) },
        )
    }

    companion object {
        private val allowedKeys = setOf("extends", "graphql", "storage", "device", "flags")
        private val validPart = Regex("[A-Za-z0-9_-]+")

        fun load(names: List<String>, root: File): FixtureSet =
            names.fold(FixtureSet()) { set, name -> set.merge(loadOne(name, root, emptyList())) }

        private fun loadOne(name: String, root: File, stack: List<String>): FixtureSet {
            if (name in stack) throw FixtureException.Invalid(name, "circular extends: ${(stack + name).joinToString(" → ")}")
            val parts = name.split(".")
            if (parts.size < 2 || !parts.all { validPart.matches(it) }) {
                throw FixtureException.Invalid(name, "fixture names look like 'Domain.scenario'")
            }
            val file = parts.dropLast(1).fold(root) { dir, part -> File(dir, part) }.let { File(it, parts.last() + ".json") }
            if (!file.isFile) throw FixtureException.NotFound(name, file.path)

            val json = try { JsonValue.parse(file.readText()) } catch (e: Exception) {
                throw FixtureException.Invalid(name, e.message ?: "invalid JSON")
            }
            val obj = json.objectValue ?: throw FixtureException.Invalid(name, "top level must be an object")
            obj.keys.firstOrNull { it !in allowedKeys }?.let {
                throw FixtureException.Invalid(name, "unknown key '$it' (allowed: extends, graphql, storage, device, flags)")
            }

            var set = FixtureSet()
            for (parent in obj["extends"]?.arrayValue.orEmpty()) {
                val parentName = parent.stringValue ?: throw FixtureException.Invalid(name, "extends must list fixture names")
                set = set.merge(loadOne(parentName, root, stack + name))
            }
            val graphql = obj["graphql"]?.arrayValue.orEmpty().mapIndexed { i, entry -> GraphQLFixture.parse(entry, name, i) }
            val storage = obj["storage"]?.objectValue.orEmpty()
            storage.forEach { (key, value) ->
                if (value !is JsonValue.Str && value != JsonValue.Null) {
                    throw FixtureException.Invalid(name, "storage['$key'] must be a string or null")
                }
            }
            return set.merge(FixtureSet(graphql, storage, obj["device"]?.objectValue.orEmpty(), obj["flags"]?.objectValue.orEmpty()))
        }
    }
}

sealed class FixtureException(val code: String, message: String) : Exception(message) {
    class NotFound(name: String, path: String) : FixtureException("FIXTURE_NOT_FOUND", "Fixture '$name' not found (looked for $path)")
    class Invalid(name: String, problem: String) : FixtureException("INVALID_FIXTURE", "Fixture '$name' is invalid: $problem")
}

class GraphQLFixture(val operation: String, val variables: Map<String, JsonValue>?, val outcome: Outcome) {
    sealed interface Outcome {
        /** The full response body, e.g. `{"data": {...}}` or `{"errors": [...]}`. */
        data class Response(val body: JsonValue) : Outcome
        data class HttpError(val status: Int) : Outcome
        data object NetworkError : Outcome
    }

    companion object {
        fun parse(json: JsonValue, fixture: String, index: Int): GraphQLFixture {
            val at = "graphql[$index]"
            val obj = json.objectValue ?: throw FixtureException.Invalid(fixture, "$at needs an 'operation' string")
            val operation = obj["operation"]?.stringValue ?: throw FixtureException.Invalid(fixture, "$at needs an 'operation' string")
            val variables = obj["variables"]?.let {
                it.objectValue ?: throw FixtureException.Invalid(fixture, "$at.variables must be an object")
            }
            val response = obj["response"]
            val error = obj["error"]
            val outcome = when {
                response != null && error == null -> Outcome.Response(response)
                error != null && response == null -> when (error["kind"]?.stringValue) {
                    "http" -> Outcome.HttpError(error["status"]?.numberValue?.toInt() ?: 500)
                    "network" -> Outcome.NetworkError
                    else -> throw FixtureException.Invalid(fixture, "$at.error.kind must be 'http' or 'network'")
                }
                else -> throw FixtureException.Invalid(fixture, "$at needs exactly one of 'response' or 'error'")
            }
            return GraphQLFixture(operation, variables, outcome)
        }
    }
}

/** Per-session matcher. Entries for an operation are consumed in declaration order; the last one repeats. */
class GraphQLFixtureMatcher(private val fixtures: List<GraphQLFixture>) {
    private val consumed = mutableSetOf<Int>()

    @Synchronized
    fun match(operation: String, variables: Map<String, JsonValue>): GraphQLFixture.Outcome? {
        val candidates = fixtures.indices.filter { i ->
            fixtures[i].operation == operation && (fixtures[i].variables ?: emptyMap()).all { (k, v) -> variables[k] == v }
        }
        val pick = candidates.firstOrNull { it !in consumed } ?: candidates.lastOrNull() ?: return null
        if (pick != candidates.last()) consumed += pick
        return fixtures[pick].outcome
    }
}
