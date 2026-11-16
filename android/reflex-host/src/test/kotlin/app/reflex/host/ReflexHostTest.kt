package app.reflex.host

import app.reflex.introspection.IntrospectionAction
import app.reflex.introspection.IntrospectionNode
import app.reflex.introspection.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

// Routing of the headless-only commands `device.set` / `device.event` to a session's DeviceControl.

class DeviceCommandsTest {
    private object Root : IntrospectionNode {
        override val name = "Root"
        override val state: Map<String, JsonValue> = emptyMap()
        override val actions: List<IntrospectionAction> = emptyList()
        override val child: IntrospectionNode? = null
    }

    private class RecordingDevice : DeviceControl {
        val calls = mutableListOf<String>()
        override suspend fun set(values: Map<String, JsonValue>) {
            calls += "set ${JsonValue.Obj(LinkedHashMap(values)).serialize()}"
        }
        override suspend fun event(type: String, payload: Map<String, JsonValue>) {
            if (type == "bad") throw HostException("BAD_REQUEST", "bad event")
            calls += "event $type ${JsonValue.Obj(LinkedHashMap(payload)).serialize()}"
        }
    }

    private fun ask(host: ReflexHost, line: String): String = runBlocking {
        var reply = ""
        host.handle(line) { reply = it }
        reply
    }

    private fun headless(device: RecordingDevice): ReflexHost {
        val root = Files.createTempDirectory("fixtures").toFile()
        return ReflexHost("test", ReflexHost.Mode.Fixtures(root) { HostedSession({ Root }, device) }, Dispatchers.Unconfined)
    }

    @Test fun routesDeviceCommandsToTheSession() {
        val device = RecordingDevice()
        val host = headless(device)
        assertEquals("""{"id":1,"ok":true,"session":"s1"}""", ask(host, """{"id":1,"cmd":"session.create","fixtures":[]}"""))
        assertEquals("""{"id":2,"ok":true}""", ask(host, """{"id":2,"cmd":"device.set","session":"s1","values":{"now":"2026-11-16T10:00:00"}}"""))
        assertEquals(
            """{"id":3,"ok":true}""",
            ask(host, """{"id":3,"cmd":"device.event","session":"s1","type":"openURL","payload":{"url":"x://today"}}"""),
        )
        assertEquals(listOf("""set {"now":"2026-11-16T10:00:00"}""", """event openURL {"url":"x://today"}"""), device.calls)
    }

    @Test fun rejectsMalformedDeviceCommands() {
        val device = RecordingDevice()
        val host = headless(device)
        ask(host, """{"id":1,"cmd":"session.create","fixtures":[]}""")
        assertEquals(
            """{"id":2,"ok":false,"error":{"code":"BAD_REQUEST","message":"'device.set' needs a 'values' object"}}""",
            ask(host, """{"id":2,"cmd":"device.set","session":"s1","values":[1]}"""),
        )
        assertEquals(
            """{"id":3,"ok":false,"error":{"code":"BAD_REQUEST","message":"'device.event' needs a 'type'"}}""",
            ask(host, """{"id":3,"cmd":"device.event","session":"s1"}"""),
        )
        assertEquals(
            """{"id":4,"ok":false,"error":{"code":"BAD_REQUEST","message":"bad event"}}""",
            ask(host, """{"id":4,"cmd":"device.event","session":"s1","type":"bad"}"""),
        )
        assertEquals(emptyList(), device.calls)
    }

    @Test fun liveSessionsDontSupportDeviceCommands() {
        val host = ReflexHost("test", ReflexHost.Mode.Live { Root }, Dispatchers.Unconfined)
        assertEquals(
            """{"id":1,"ok":false,"error":{"code":"UNSUPPORTED","message":"'device.set' only works in headless sessions"}}""",
            ask(host, """{"id":1,"cmd":"device.set","values":{}}"""),
        )
        assertEquals(
            """{"id":2,"ok":false,"error":{"code":"UNSUPPORTED","message":"'device.event' only works in headless sessions"}}""",
            ask(host, """{"id":2,"cmd":"device.event","type":"openURL","payload":{"url":"x://today"}}"""),
        )
    }
}
