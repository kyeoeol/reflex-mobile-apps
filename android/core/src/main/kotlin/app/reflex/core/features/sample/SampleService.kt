package app.reflex.core.features.sample

import app.reflex.core.foundation.GraphQLClient
import app.reflex.core.foundation.GraphQLOperation
import app.reflex.core.foundation.NoVariables
import kotlinx.serialization.Serializable

// REFERENCE FEATURE: "Sample" exists only to show the architecture end to end. Delete it (Swift, Kotlin,
// views, fixtures/Sample, tests/behavior/sample.cli-test.ts) once the first real feature lands.

@Serializable
data class SampleItem(val id: String, val title: String, val count: Int)

/** Side effects for the Sample screens. An interface, injected through ViewModel constructors. */
interface SampleService {
    suspend fun items(): List<SampleItem>
    suspend fun item(id: String): SampleItem
    suspend fun save(id: String, count: Int)
}

class SampleSaveException(message: String) : Exception(message)

/** The real implementation: real GraphQL client; in the CLI only the transport underneath is a fixture. */
class GraphQLSampleService(private val client: GraphQLClient) : SampleService {
    override suspend fun items(): List<SampleItem> = client.execute(SampleOperations.items()).items

    override suspend fun item(id: String): SampleItem = client.execute(SampleOperations.item(id)).item

    override suspend fun save(id: String, count: Int) {
        val result = client.execute(SampleOperations.save(id, count))
        result.sampleSave.userErrors.firstOrNull()?.let { throw SampleSaveException(it.message) }
    }
}

/** Operation names must match iOS and the fixtures. */
internal object SampleOperations {
    @Serializable data class ItemsData(val items: List<SampleItem>)
    @Serializable data class ItemVariables(val id: String)
    @Serializable data class ItemData(val item: SampleItem)
    @Serializable data class SaveVariables(val id: String, val count: Int)
    @Serializable data class UserError(val message: String)
    @Serializable data class SavePayload(val userErrors: List<UserError>)
    @Serializable data class SaveData(val sampleSave: SavePayload)

    fun items() = GraphQLOperation(
        "SampleItems", "query SampleItems { items { id title count } }",
        NoVariables, NoVariables.serializer(), ItemsData.serializer(),
    )

    fun item(id: String) = GraphQLOperation(
        "SampleItem", "query SampleItem(\$id: ID!) { item(id: \$id) { id title count } }",
        ItemVariables(id), ItemVariables.serializer(), ItemData.serializer(),
    )

    fun save(id: String, count: Int) = GraphQLOperation(
        "SampleSave", "mutation SampleSave(\$id: ID!, \$count: Int!) { sampleSave(id: \$id, count: \$count) { userErrors { message } } }",
        SaveVariables(id, count), SaveVariables.serializer(), SaveData.serializer(),
    )
}
