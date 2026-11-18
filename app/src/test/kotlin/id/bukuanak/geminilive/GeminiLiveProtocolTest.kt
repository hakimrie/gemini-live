package id.bukuanak.geminilive

import id.bukuanak.geminilive.audio.AudioConfig
import id.bukuanak.geminilive.live.ClientMessage
import id.bukuanak.geminilive.live.Content
import id.bukuanak.geminilive.live.GenerationConfig
import id.bukuanak.geminilive.live.InlineData
import id.bukuanak.geminilive.live.Part
import id.bukuanak.geminilive.live.PrebuiltVoiceConfig
import id.bukuanak.geminilive.live.RealtimeInput
import id.bukuanak.geminilive.live.ServerMessage
import id.bukuanak.geminilive.live.SetupConfig
import id.bukuanak.geminilive.live.SpeechConfig
import id.bukuanak.geminilive.live.VoiceConfig
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
                model = "models/gemini-3.8-live",
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

        assertTrue(serialized.contains("models/gemini-3.8-live"))
        assertTrue(serialized.contains("\"voiceName\":\"Puck\""))
        assertTrue(serialized.contains("\"responseModalities\":[\"AUDIO\"]"))
        assertTrue(serialized.contains("Concise voice assistant."))
    }

    @Test
    fun realtimeInputMessage_serialization_containsBase64AudioChunk() {
        val dummyBase64 = "AAAA////AAAA////"
        val message = ClientMessage(
            realtimeInput = RealtimeInput(
                audio = InlineData(
                    mimeType = AudioConfig.MIME_TYPE_PCM_16KHZ,
                    data = dummyBase64
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
                      "inlineData": {
                        "mimeType": "audio/pcm;rate=24000",
                        "data": "UklGRgAAAABXQVZFZm10"
                      }
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
        assertEquals("audio/pcm;rate=24000", audioPart?.inlineData?.mimeType)
        assertEquals("UklGRgAAAABXQVZFZm10", audioPart?.inlineData?.data)

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
