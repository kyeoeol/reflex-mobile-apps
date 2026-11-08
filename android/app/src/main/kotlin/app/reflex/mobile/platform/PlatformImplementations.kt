package app.reflex.mobile.platform

import android.content.Context
import android.content.pm.PackageManager
import app.reflex.core.foundation.DeviceCapabilities
import app.reflex.core.foundation.FeatureFlags
import app.reflex.core.foundation.GraphQLRequest
import app.reflex.core.foundation.GraphQLTransport
import app.reflex.core.foundation.GraphQLTransportException
import app.reflex.core.foundation.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Real implementations of Core's platform interfaces (core/.../foundation/Platform.kt).
// Their fixture counterparts live in android/cli (Main.kt). Keep each pair in sync.

/** Placeholder persistence; move secrets to the Android Keystore when the product needs them. */
class PreferencesStorage(context: Context) : Storage {
    private val prefs = context.getSharedPreferences("reflex.storage", Context.MODE_PRIVATE)
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun write(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

class HttpGraphQLTransport(private val endpoint: String) : GraphQLTransport {
    override suspend fun execute(request: GraphQLRequest): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("operationName", JsonPrimitive(request.operationName))
            put("query", JsonPrimitive(request.query))
            put("variables", Json.parseToJsonElement(request.variables))
        }.toString()
        try {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            if (status !in 200..299) throw GraphQLTransportException.Http(status)
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw GraphQLTransportException.Network(e.message ?: "network error")
        }
    }
}

class SystemDeviceCapabilities(private val context: Context) : DeviceCapabilities {
    override val biometricsAvailable: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FACE)
}

/** Placeholder: swap for remote config when the product needs it. */
class LocalFeatureFlags(private val enabled: Set<String> = emptySet()) : FeatureFlags {
    override fun isEnabled(flag: String): Boolean = flag in enabled
}
