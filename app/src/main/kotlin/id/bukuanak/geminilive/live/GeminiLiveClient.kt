package id.bukuanak.geminilive.live

import java.util.Base64
import android.util.Log
import id.bukuanak.geminilive.audio.AudioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

enum class LiveConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SPEAKING,
    ERROR
}

sealed class LiveEvent {
    data class SetupCompleted(val timestamp: Long = System.currentTimeMillis()) : LiveEvent()
    data class AudioReceived(val pcmData: ByteArray) : LiveEvent()
    data class TextReceived(val text: String) : LiveEvent()
    data class Interrupted(val reason: String = "User barge-in detected by Gemini") : LiveEvent()
    data class TurnComplete(val timestamp: Long = System.currentTimeMillis()) : LiveEvent()
    data class Error(val message: String, val throwable: Throwable? = null) : LiveEvent()
    data class LogMessage(val message: String, val isWarning: Boolean = false) : LiveEvent()
}

/**
 * Manages the WebSocket connection to the Gemini Multimodal Live API
 * (BidiGenerateContent) using OkHttp.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String = "gemini-3.8-live",
    private val voiceName: String = "Puck",
    private val systemPrompt: String = "You are a fast, expressive voice assistant. Respond conversationally and concisely.",
    private val webSocketFactory: WebSocket.Factory = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val setupTimeoutMillis: Long = 20_000L
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private var webSocket: WebSocket? = null
    private var setupTimeoutJob: Job? = null
    private var connectionId = 0L
    private var sentAudioChunks = 0L
    private var receivedAudioChunks = 0L

    private val _connectionState = MutableStateFlow(LiveConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    @Synchronized
    fun connect() {
        if (_connectionState.value == LiveConnectionState.SPEAKING ||
            _connectionState.value == LiveConnectionState.CONNECTED ||
            _connectionState.value == LiveConnectionState.CONNECTING) {
            return
        }

        if (apiKey.isBlank()) {
            failConnection("API Key is missing. Please set your Gemini API Key in Settings.")
            return
        }

        sentAudioChunks = 0L
        receivedAudioChunks = 0L
        _connectionState.value = LiveConnectionState.CONNECTING
        emitLog("Initiating WebSocket connection to Gemini Live API...")

        val id = ++connectionId
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = webSocketFactory.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = forConnection(id) {
                emitLog("WebSocket transport opened. Sending setup handshake...")
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = forConnection(id) {
                handleIncomingMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = forConnection(id) {
                handleIncomingMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = forConnection(id) {
                handleClose(webSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = forConnection(id) {
                handleClose(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = forConnection(id) {
                val status = response?.let { " (HTTP ${it.code})" }.orEmpty()
                failConnection("WebSocket connection failure$status: ${t.message ?: "Unknown error"}", t)
            }
        })
        setupTimeoutJob = scope.launch {
            delay(setupTimeoutMillis)
            forConnection(id) {
                if (_connectionState.value == LiveConnectionState.CONNECTING) {
                    failConnection("Gemini setup timed out after ${setupTimeoutMillis / 1000} seconds. Check your network, API key, and selected Live model.")
                }
            }
        }
    }

    private fun forConnection(id: Long, action: () -> Unit) = synchronized(this) {
        if (id == connectionId) action()
    }

    private fun handleClose(ws: WebSocket, code: Int, reason: String) {
        val wasConnecting = _connectionState.value == LiveConnectionState.CONNECTING
        ws.close(code, reason)
        if (wasConnecting || code != 1000) {
            failConnection("Gemini closed ${if (wasConnecting) "during setup" else "the session"} ($code): ${reason.ifBlank { "No reason provided" }}")
        } else {
            setupTimeoutJob?.cancel()
            connectionId++
            webSocket = null
            _connectionState.value = LiveConnectionState.DISCONNECTED
            emitLog("WebSocket closed: $code / $reason")
        }
    }

    private fun failConnection(message: String, throwable: Throwable? = null) {
        setupTimeoutJob?.cancel()
        connectionId++
        val socket = webSocket
        webSocket = null
        _connectionState.value = LiveConnectionState.ERROR
        socket?.cancel()
        emitLog(message, isWarning = true)
        emitEvent(LiveEvent.Error(message, throwable))
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setupPayload = ClientMessage(
            setup = SetupConfig(
                model = "models/${model.trim().removePrefix("models/")}",
                generationConfig = GenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = SpeechConfig(
                        voiceConfig = VoiceConfig(
                            prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = voiceName)
                        )
                    )
                ),
                systemInstruction = Content(
                    parts = listOf(Part(text = systemPrompt))
                )
            )
        )

        val setupJson = json.encodeToString(ClientMessage.serializer(), setupPayload)
        val success = ws.send(setupJson)
        if (success) {
            emitLog("Setup message dispatched (model: $model, voice: $voiceName)")
        } else {
            failConnection("Failed to dispatch setup message to WebSocket")
        }
    }

    /**
     * Streams a 16kHz PCM audio chunk captured from microphone to Gemini.
     */
    fun sendAudioChunk(pcmBytes: ByteArray) {
        val ws = webSocket ?: return
        if (_connectionState.value != LiveConnectionState.CONNECTED &&
            _connectionState.value != LiveConnectionState.SPEAKING) {
            return
        }

        try {
            val base64Data = Base64.getEncoder().encodeToString(pcmBytes)
            val chunkMessage = ClientMessage(
                realtimeInput = RealtimeInput(
                    audio = InlineData(
                        mimeType = AudioConfig.MIME_TYPE_PCM_16KHZ,
                        data = base64Data
                    )
                )
            )
            val payloadJson = json.encodeToString(ClientMessage.serializer(), chunkMessage)
            if (!ws.send(payloadJson)) {
                emitEvent(LiveEvent.Error("Failed to send microphone audio: WebSocket is closed or its queue is full."))
                return
            }
            sentAudioChunks++
            if (sentAudioChunks == 1L || sentAudioChunks % 50L == 0L) {
                emitLog("Microphone audio queued: chunks=$sentAudioChunks, chunkBytes=${pcmBytes.size}, pendingBytes=${ws.queueSize()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending realtime audio chunk", e)
        }
    }

    /**
     * Send text input turn into the live conversation.
     */
    fun sendTextMessage(text: String) {
        val ws = webSocket ?: return
        try {
            val contentMessage = ClientMessage(
                clientContent = ClientContent(
                    turns = listOf(
                        Content(
                            role = "user",
                            parts = listOf(Part(text = text))
                        )
                    ),
                    turnComplete = true
                )
            )
            val payloadJson = json.encodeToString(ClientMessage.serializer(), contentMessage)
            ws.send(payloadJson)
            emitLog("Sent text turn: \"$text\"")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text message", e)
        }
    }

    private fun handleIncomingMessage(text: String) {
        try {
            val serverMessage = json.decodeFromString(ServerMessage.serializer(), text)

            serverMessage.error?.let {
                failConnection("Gemini API error (${it.code}): ${it.message}")
                return
            }

            if (serverMessage.setupComplete != null) {
                setupTimeoutJob?.cancel()
                _connectionState.value = LiveConnectionState.CONNECTED
                emitLog("Handshake complete: Gemini Live session active!")
                emitEvent(LiveEvent.SetupCompleted())
                return
            }

            val serverContent = serverMessage.serverContent ?: return

            // 1. BARGE-IN INTERRUPT DETECTION:
            // Gemini server detected that the user started speaking while it was outputting audio.
            if (serverContent.interrupted == true) {
                _connectionState.value = LiveConnectionState.CONNECTED
                emitLog("⚡ [BARGE-IN] Gemini detected user interruption! Cutting off playback.")
                emitEvent(LiveEvent.Interrupted())
            }

            // 2. MODEL TURN AUDIO & TEXT:
            serverContent.modelTurn?.parts?.forEach { part ->
                if (part.text != null && part.text.isNotBlank()) {
                    emitEvent(LiveEvent.TextReceived(part.text))
                }

                val audio = part.inlineData
                if (audio != null && audio.mimeType.startsWith("audio/pcm")) {
                    try {
                        val audioBytes = Base64.getDecoder().decode(audio.data)
                        receivedAudioChunks++
                        if (receivedAudioChunks == 1L || receivedAudioChunks % 50L == 0L) {
                            emitLog("Gemini audio received: chunks=$receivedAudioChunks, chunkBytes=${audioBytes.size}")
                        }
                        _connectionState.value = LiveConnectionState.SPEAKING
                        emitEvent(LiveEvent.AudioReceived(audioBytes))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decode base64 audio payload", e)
                    }
                }
            }

            // 3. TURN COMPLETE:
            if (serverContent.turnComplete == true) {
                _connectionState.value = LiveConnectionState.CONNECTED
                emitEvent(LiveEvent.TurnComplete())
                emitLog("Model finished turn.")
            }
        } catch (e: Exception) {
            failConnection("Invalid message from Gemini: ${e.message}", e)
        }
    }

    @Synchronized
    fun disconnect() {
        connectionId++
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        emitLog("Disconnecting Gemini Live session...")
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket", e)
        }
        webSocket = null
        _connectionState.value = LiveConnectionState.DISCONNECTED
    }

    private fun emitLog(message: String, isWarning: Boolean = false) {
        Log.d(TAG, message)
        emitEvent(LiveEvent.LogMessage(message, isWarning))
    }

    private fun emitEvent(event: LiveEvent) {
        scope.launch {
            _events.emit(event)
        }
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
    }
}
