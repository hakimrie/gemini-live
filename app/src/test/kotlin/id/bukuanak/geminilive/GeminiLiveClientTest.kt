package id.bukuanak.geminilive

import id.bukuanak.geminilive.live.GeminiLiveClient
import id.bukuanak.geminilive.live.LiveConnectionState
import id.bukuanak.geminilive.live.LiveEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Protocol
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiLiveClientTest {
    private class Transport : WebSocket.Factory, WebSocket {
        lateinit var listener: WebSocketListener
        private lateinit var request: Request
        var cancelled = false
        val sent = mutableListOf<String>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.request = request
            this.listener = listener
            return this
        }
        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
    }

    @Test
    fun binarySetupResponse_connectsSession() = runTest {
        val transport = Transport()
        val client = GeminiLiveClient("test-key", webSocketFactory = transport, scope = backgroundScope)
        client.connect()
        transport.listener.onMessage(transport, """{"setupComplete":{}}""".encodeUtf8())
        assertEquals(LiveConnectionState.CONNECTED, client.connectionState.value)
        client.disconnect()
    }

    @Test
    fun silentServer_timesOutInsteadOfConnectingForever() = runTest {
        val transport = Transport()
        val client = GeminiLiveClient("test-key", webSocketFactory = transport, scope = backgroundScope, setupTimeoutMillis = 1_000)
        client.connect()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(LiveConnectionState.ERROR, client.connectionState.value)
        assertTrue(transport.cancelled)
    }

    @Test
    fun rejectedSetup_preservesCloseReasonAsError() = runTest {
        val transport = Transport()
        val client = GeminiLiveClient("test-key", webSocketFactory = transport, scope = backgroundScope)
        client.connect()
        transport.listener.onClosing(transport, 1008, "Model is not supported")
        transport.listener.onClosed(transport, 1008, "Model is not supported")
        assertEquals(LiveConnectionState.ERROR, client.connectionState.value)
        client.disconnect()
    }

    @Test
    fun oldSocketCallback_cannotChangeDisconnectedState() = runTest {
        val transport = Transport()
        val client = GeminiLiveClient("test-key", webSocketFactory = transport, scope = backgroundScope)
        client.connect()
        client.disconnect()
        transport.listener.onMessage(transport, """{"setupComplete":{}}""")
        assertEquals(LiveConnectionState.DISCONNECTED, client.connectionState.value)
    }

    @Test
    fun textSetupResponse_cancelsTimeout() = runTest {
        val transport = Transport()
        val client = GeminiLiveClient("test-key", webSocketFactory = transport, scope = backgroundScope, setupTimeoutMillis = 1_000)
        client.connect()
        transport.listener.onMessage(transport, """{"setupComplete":{}}""")
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(LiveConnectionState.CONNECTED, client.connectionState.value)
        assertFalse(transport.cancelled)
        client.disconnect()
    }

    @Test
    fun setupMessage_hasOnlySetupFieldAndQualifiedModel() = runTest {
        val transport = Transport()
        val client = GeminiLiveClient("test-key", model = "example-live-model", webSocketFactory = transport, scope = backgroundScope)
        client.connect()
        val response = Response.Builder().request(transport.request()).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build()
        transport.listener.onOpen(transport, response)
        val payload = Json.parseToJsonElement(transport.sent.single()).jsonObject
        assertEquals(setOf("setup"), payload.keys)
        assertEquals("models/example-live-model", payload.getValue("setup").jsonObject.getValue("model").jsonPrimitive.content)
        client.disconnect()
    }

    @Test
    fun binaryAudioResponse_emitsDecodedPcm() = runTest {
        val transport = Transport()
        val client = GeminiLiveClient("test-key", webSocketFactory = transport, scope = backgroundScope)
        val events = mutableListOf<LiveEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { events += it } }
        client.connect()
        transport.listener.onMessage(transport, """{"setupComplete":{}}""".encodeUtf8())
        transport.listener.onMessage(transport, """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQID"}}]}}}""".encodeUtf8())
        runCurrent()
        assertArrayEquals(byteArrayOf(1, 2, 3), events.filterIsInstance<LiveEvent.AudioReceived>().single().pcmData)
        assertEquals(LiveConnectionState.SPEAKING, client.connectionState.value)
        client.disconnect()
    }

    @Test
    fun apiErrorResponse_surfacesMessage() = runTest {
        val transport = Transport()
        val client = GeminiLiveClient("test-key", webSocketFactory = transport, scope = backgroundScope)
        val events = mutableListOf<LiveEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { events += it } }
        client.connect()
        transport.listener.onMessage(transport, """{"error":{"code":400,"message":"Invalid API key"}}""".encodeUtf8())
        runCurrent()
        assertEquals(LiveConnectionState.ERROR, client.connectionState.value)
        assertTrue(events.filterIsInstance<LiveEvent.Error>().single().message.contains("Invalid API key"))
    }

    @Test
    fun microphoneAudio_usesRealtimeAudioField() = runTest {
        val transport = Transport()
        val client = GeminiLiveClient("test-key", webSocketFactory = transport, scope = backgroundScope)
        client.connect()
        transport.listener.onMessage(transport, """{"setupComplete":{}}""")
        client.sendAudioChunk(byteArrayOf(1, 2, 3))
        val message = Json.parseToJsonElement(transport.sent.single()).jsonObject
        val input = message.getValue("realtimeInput").jsonObject
        assertEquals(setOf("audio"), input.keys)
        assertEquals("audio/pcm;rate=16000", input.getValue("audio").jsonObject.getValue("mimeType").jsonPrimitive.content)
        assertEquals("AQID", input.getValue("audio").jsonObject.getValue("data").jsonPrimitive.content)
        client.disconnect()
    }

}
