package id.bukuanak.geminilive.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Client -> Server Payloads ---

@Serializable
data class ClientMessage(
    val setup: SetupConfig? = null,
    val realtimeInput: RealtimeInput? = null,
    val clientContent: ClientContent? = null
)

@Serializable
data class SetupConfig(
    val model: String,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@Serializable
data class GenerationConfig(
    val responseModalities: List<String> = listOf("AUDIO"),
    val speechConfig: SpeechConfig? = null
)

@Serializable
data class SpeechConfig(
    val voiceConfig: VoiceConfig
)

@Serializable
data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

@Serializable
data class PrebuiltVoiceConfig(
    val voiceName: String = "Puck"
)

@Serializable
data class RealtimeInput(
    val mediaChunks: List<MediaChunk>
)

@Serializable
data class MediaChunk(
    val mimeType: String,
    val data: String // Base64 PCM data
)

@Serializable
data class ClientContent(
    val turns: List<Content>,
    val turnComplete: Boolean = true
)

@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@Serializable
data class InlineData(
    val mimeType: String,
    val data: String
)

// --- Server -> Client Payloads ---

@Serializable
data class ServerMessage(
    val setupComplete: SetupComplete? = null,
    val serverContent: ServerContent? = null,
    val toolCall: ToolCall? = null
)

@Serializable
class SetupComplete

@Serializable
data class ServerContent(
    val modelTurn: ModelTurn? = null,
    val turnComplete: Boolean? = null,
    val interrupted: Boolean? = null
)

@Serializable
data class ModelTurn(
    val parts: List<ModelPart> = emptyList()
)

@Serializable
data class ModelPart(
    val text: String? = null,
    val mimeType: String? = null,
    val data: String? = null
)

@Serializable
data class ToolCall(
    val functionCalls: List<FunctionCall> = emptyList()
)

@Serializable
data class FunctionCall(
    val name: String,
    val id: String? = null
)
