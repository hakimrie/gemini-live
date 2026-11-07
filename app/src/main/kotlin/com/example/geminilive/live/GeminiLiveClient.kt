package com.example.geminilive.live

import android.util.Base64
import android.util.Log
import com.example.geminilive.audio.AudioConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
    private val model: String = "models/gemini-2.0-flash-exp",
    private val voiceName: String = "Puck",
    private val systemPrompt: String = "You are a fast, expressive voice assistant. Respond conversationally and concisely."
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // No read timeout for persistent streaming
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _connectionState = MutableStateFlow(LiveConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun connect() {
        if (_connectionState.value == LiveConnectionState.CONNECTED ||
            _connectionState.value == LiveConnectionState.CONNECTING) {
            return
        }

        if (apiKey.isBlank()) {
            emitEvent(LiveEvent.Error("API Key is missing. Please set your Gemini API Key in Settings."))
            return
        }

        _connectionState.value = LiveConnectionState.CONNECTING
        emitLog("Initiating WebSocket connection to Gemini Live API...")

        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                emitLog("WebSocket transport opened. Sending setup handshake...")
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                emitLog("WebSocket closing: $code / $reason")
                _connectionState.value = LiveConnectionState.DISCONNECTED
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                emitLog("WebSocket closed: $code / $reason")
                _connectionState.value = LiveConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = "WebSocket connection failure: ${t.message ?: "Unknown error"}"
                Log.e(TAG, errorMsg, t)
                emitLog(errorMsg, isWarning = true)
                _connectionState.value = LiveConnectionState.ERROR
                emitEvent(LiveEvent.Error(errorMsg, t))
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setupPayload = ClientMessage(
            setup = SetupConfig(
                model = model,
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
            emitLog("Failed to dispatch setup message to WebSocket", isWarning = true)
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
            val base64Data = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
            val chunkMessage = ClientMessage(
                realtimeInput = RealtimeInput(
                    mediaChunks = listOf(
                        MediaChunk(
                            mimeType = AudioConfig.MIME_TYPE_PCM_16KHZ,
                            data = base64Data
                        )
                    )
                )
            )
            val payloadJson = json.encodeToString(ClientMessage.serializer(), chunkMessage)
            ws.send(payloadJson)
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

            if (serverMessage.setupComplete != null) {
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

                if (part.data != null && (part.mimeType == null || part.mimeType.startsWith("audio/pcm"))) {
                    try {
                        val audioBytes = Base64.decode(part.data, Base64.NO_WRAP)
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
            Log.e(TAG, "Error parsing server message JSON: $text", e)
        }
    }

    fun disconnect() {
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
