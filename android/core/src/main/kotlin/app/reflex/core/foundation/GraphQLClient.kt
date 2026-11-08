package app.reflex.core.foundation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A GraphQL operation. [operationName] is what fixtures match on and must be identical on iOS. */
class GraphQLOperation<V, D>(
    val operationName: String,
    val document: String,
    val variables: V,
    val variablesSerializer: KSerializer<V>,
    val dataSerializer: KSerializer<D>,
)

@Serializable
object NoVariables

class GraphQLServerException(val messages: List<String>) : Exception(messages.joinToString("; "))

/** The real client. Only [GraphQLTransport] differs between the app and the CLI. */
class GraphQLClient(private val transport: GraphQLTransport) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun <V, D> execute(operation: GraphQLOperation<V, D>): D {
        val variables = json.encodeToString(operation.variablesSerializer, operation.variables)
        val body = transport.execute(GraphQLRequest(operation.operationName, operation.document, variables))
        val envelope = json.parseToJsonElement(body).jsonObject
        val errors = envelope["errors"]?.takeIf { it != JsonNull }?.jsonArray.orEmpty()
        if (errors.isNotEmpty()) throw GraphQLServerException(errors.map { it.jsonObject["message"]?.jsonPrimitive?.content.orEmpty() })
        val data = envelope["data"]?.takeIf { it != JsonNull } ?: throw GraphQLServerException(listOf("missing data"))
        return json.decodeFromJsonElement(operation.dataSerializer, data)
    }
}
