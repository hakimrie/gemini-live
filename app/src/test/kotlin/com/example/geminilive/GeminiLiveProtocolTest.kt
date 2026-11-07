package com.example.geminilive

import com.example.geminilive.audio.AudioConfig
import com.example.geminilive.live.ClientMessage
import com.example.geminilive.live.Content
import com.example.geminilive.live.GenerationConfig
import com.example.geminilive.live.MediaChunk
import com.example.geminilive.live.Part
import com.example.geminilive.live.PrebuiltVoiceConfig
import com.example.geminilive.live.RealtimeInput
import com.example.geminilive.live.ServerMessage
import com.example.geminilive.live.SetupConfig
import com.example.geminilive.live.SpeechConfig
import com.example.geminilive.live.VoiceConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiLiveProtocolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Test
    fun audioConfig_matchesGeminiLiveSpecifications() {
        // Gemini input must be 16kHz linear PCM mono
        assertEquals(16000, AudioConfig.INPUT_SAMPLE_RATE)
        assertEquals("audio/pcm;rate=16000", AudioConfig.MIME_TYPE_PCM_16KHZ)
        assertEquals(3200, AudioConfig.INPUT_CHUNK_BYTES) // 100ms chunk

        // Gemini output is 24kHz linear PCM mono
        assertEquals(24000, AudioConfig.OUTPUT_SAMPLE_RATE)
        assertEquals("audio/pcm;rate=24000", AudioConfig.MIME_TYPE_PCM_24KHZ)
    }

    @Test
    fun setupMessage_serialization_containsRequiredFields() {
        val setupPayload = ClientMessage(
            setup = SetupConfig(
                model = "models/gemini-2.0-flash-exp",
                generationConfig = GenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = SpeechConfig(
                        voiceConfig = VoiceConfig(
                            prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = "Puck")
                        )
                    )
                ),
                systemInstruction = Content(
                    parts = listOf(Part(text = "Concise voice assistant."))
                )
            )
        )

        val serialized = json.encodeToString(ClientMessage.serializer(), setupPayload)

        assertTrue(serialized.contains("models/gemini-2.0-flash-exp"))
        assertTrue(serialized.contains("\"voiceName\":\"Puck\""))
        assertTrue(serialized.contains("\"responseModalities\":[\"AUDIO\"]"))
        assertTrue(serialized.contains("Concise voice assistant."))
    }

    @Test
    fun realtimeInputMessage_serialization_containsBase64AudioChunk() {
        val dummyBase64 = "AAAA////AAAA////"
        val message = ClientMessage(
            realtimeInput = RealtimeInput(
                mediaChunks = listOf(
                    MediaChunk(
                        mimeType = AudioConfig.MIME_TYPE_PCM_16KHZ,
                        data = dummyBase64
                    )
                )
            )
        )

        val serialized = json.encodeToString(ClientMessage.serializer(), message)
        assertTrue(serialized.contains("audio/pcm;rate=16000"))
        assertTrue(serialized.contains(dummyBase64))
    }

    @Test
    fun serverMessage_interrupted_bargeInParsedCorrectly() {
        val incomingJson = """
            {
              "serverContent": {
                "interrupted": true
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString(ServerMessage.serializer(), incomingJson)
        assertNotNull(parsed.serverContent)
        assertEquals(true, parsed.serverContent?.interrupted)
    }

    @Test
    fun serverMessage_modelTurnAudio_parsedCorrectly() {
        val incomingJson = """
            {
              "serverContent": {
                "modelTurn": {
                  "parts": [
                    {
                      "mimeType": "audio/pcm;rate=24000",
                      "data": "UklGRgAAAABXQVZFZm10"
                    },
                    {
                      "text": "Hello, how can I assist you today?"
                    }
                  ]
                },
                "turnComplete": true
              }
            }
        """.trimIndent()

        val parsed = json.decodeFromString(ServerMessage.serializer(), incomingJson)
        val serverContent = parsed.serverContent
        assertNotNull(serverContent)
        assertEquals(true, serverContent?.turnComplete)
        assertEquals(2, serverContent?.modelTurn?.parts?.size)

        val audioPart = serverContent?.modelTurn?.parts?.get(0)
        assertEquals("audio/pcm;rate=24000", audioPart?.mimeType)
        assertEquals("UklGRgAAAABXQVZFZm10", audioPart?.data)

        val textPart = serverContent?.modelTurn?.parts?.get(1)
        assertEquals("Hello, how can I assist you today?", textPart?.text)
    }

    @Test
    fun serverMessage_setupComplete_parsedCorrectly() {
        val incomingJson = """
            {
              "setupComplete": {}
            }
        """.trimIndent()

        val parsed = json.decodeFromString(ServerMessage.serializer(), incomingJson)
        assertNotNull(parsed.setupComplete)
    }
}
