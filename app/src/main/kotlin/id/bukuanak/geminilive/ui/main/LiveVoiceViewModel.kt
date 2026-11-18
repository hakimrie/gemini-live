package id.bukuanak.geminilive.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.bukuanak.geminilive.audio.AudioEffectsDiagnostics
import id.bukuanak.geminilive.audio.AudioRecorder
import id.bukuanak.geminilive.audio.AudioTrackPlayer
import id.bukuanak.geminilive.audio.EchoCancellationManager
import id.bukuanak.geminilive.data.AppConfig
import id.bukuanak.geminilive.data.AppSettings
import id.bukuanak.geminilive.live.GeminiLiveClient
import id.bukuanak.geminilive.live.LiveConnectionState
import id.bukuanak.geminilive.live.LiveEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val time: String,
    val message: String,
    val isBargeIn: Boolean = false,
    val isError: Boolean = false
)

data class LiveUiState(
    val connectionState: LiveConnectionState = LiveConnectionState.DISCONNECTED,
    val isMuted: Boolean = false,
    val userMicRms: Float = 0f,
    val geminiSpeakerRms: Float = 0f,
    val isGeminiSpeaking: Boolean = false,
    val bargeInCount: Int = 0,
    val lastBargeInTime: String? = null,
    val audioDiagnostics: AudioEffectsDiagnostics? = null,
    val logs: List<LogEntry> = emptyList(),
    val transcript: String = "",
    val config: AppConfig,
    val isSettingsOpen: Boolean = false,
    val errorMessage: String? = null
)

class LiveVoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = AppSettings(application)
    private val echoCancellationManager = EchoCancellationManager(application)
    private val audioTrackPlayer = AudioTrackPlayer()

    private var liveClient: GeminiLiveClient? = null
    private var clientEventJob: Job? = null
    private var connectionStateJob: Job? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val audioRecorder = AudioRecorder(
        context = application,
        echoCancellationManager = echoCancellationManager,
        onAudioChunkReady = { chunk ->
            liveClient?.sendAudioChunk(chunk)
        }
    )

    private val _uiState = MutableStateFlow(
        LiveUiState(
            config = settings.getConfig(),
            audioDiagnostics = AudioEffectsDiagnostics(
                aecAvailable = echoCancellationManager.isAecAvailable,
                nsAvailable = echoCancellationManager.isNsAvailable,
                agcAvailable = echoCancellationManager.isAgcAvailable
            )
        )
    )
    val uiState = _uiState.asStateFlow()

    init {
        // Collect RMS audio meters for visualizers
        viewModelScope.launch {
            audioRecorder.micRms.collect { rms ->
                _uiState.update { it.copy(userMicRms = rms) }
            }
        }

        viewModelScope.launch {
            audioTrackPlayer.outputRms.collect { rms ->
                _uiState.update { it.copy(geminiSpeakerRms = rms) }
            }
        }

        viewModelScope.launch {
            audioTrackPlayer.isPlaying.collect { isPlaying ->
                audioRecorder.isModelSpeaking = isPlaying
                _uiState.update { it.copy(isGeminiSpeaking = isPlaying) }
            }
        }

        viewModelScope.launch {
            audioRecorder.effectsDiagnostics.collect { diag ->
                if (diag != null) {
                    _uiState.update { it.copy(audioDiagnostics = diag) }
                }
            }
        }
    }

    fun startSession() {
        val config = settings.getConfig()
        if (config.apiKey.isBlank()) {
            _uiState.update {
                it.copy(
                    errorMessage = "Gemini API key is required. Please open Settings to enter your key.",
                    isSettingsOpen = true
                )
            }
            return
        }

        disconnect()

        addLog("Starting Gemini Live voice session...")
        audioRecorder.enableHardwareAec = config.enableHardwareAec
        audioRecorder.enableSoftwareEchoGate = config.enableSoftwareEchoGate

        audioTrackPlayer.initAudioTrack()

        val client = GeminiLiveClient(
            apiKey = config.apiKey,
            model = config.model,
            voiceName = config.voice,
            systemPrompt = config.systemPrompt
        )
        liveClient = client

        // Listen to client events
        clientEventJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                handleLiveEvent(event)
            }
        }

        // Listen to connection state
        connectionStateJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.connectionState.collect { state ->
                if (state == LiveConnectionState.ERROR || state == LiveConnectionState.DISCONNECTED) {
                    audioRecorder.stopRecording()
                    audioTrackPlayer.stopAndFlush()
                }
                _uiState.update { it.copy(connectionState = state) }
            }
        }

        client.connect()
    }

    private fun handleLiveEvent(event: LiveEvent) {
        when (event) {
            is LiveEvent.SetupCompleted -> {
                addLog("Gemini ready! Starting microphone stream with AEC...")
                val recordOk = audioRecorder.startRecording()
                if (!recordOk) {
                    addLog("Failed to initialize microphone with AEC", isError = true)
                }
            }

            is LiveEvent.AudioReceived -> {
                audioTrackPlayer.enqueueAudio(event.pcmData)
            }

            is LiveEvent.TextReceived -> {
                _uiState.update {
                    it.copy(transcript = (it.transcript + " " + event.text).trim())
                }
            }

            is LiveEvent.Interrupted -> {
                // INSTANT BARGE-IN:
                // Stop and flush AudioTrack immediately so audio is cut off in < 10ms!
                audioTrackPlayer.stopAndFlush()
                val now = timeFormat.format(Date())
                _uiState.update {
                    it.copy(
                        bargeInCount = it.bargeInCount + 1,
                        lastBargeInTime = now
                    )
                }
                addLog("⚡ [BARGE-IN TRIGGERED] Gemini detected user voice! Flushed audio output buffer.", isBargeIn = true)
            }

            is LiveEvent.TurnComplete -> {
                addLog("Turn completed.")
            }

            is LiveEvent.Error -> {
                addLog(event.message, isError = true)
                _uiState.update { it.copy(errorMessage = event.message) }
            }

            is LiveEvent.LogMessage -> {
                addLog(event.message, isError = event.isWarning)
            }
        }
    }

    /**
     * Manual user barge-in action (tap to interrupt).
     */
    fun manualBargeIn() {
        audioTrackPlayer.stopAndFlush()
        val now = timeFormat.format(Date())
        _uiState.update {
            it.copy(
                bargeInCount = it.bargeInCount + 1,
                lastBargeInTime = now
            )
        }
        addLog("⚡ [MANUAL BARGE-IN] User tapped interrupt: Audio flushed.", isBargeIn = true)
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        audioRecorder.isMuted = newMuted
        _uiState.update { it.copy(isMuted = newMuted) }
        addLog(if (newMuted) "Microphone muted" else "Microphone unmuted")
    }

    fun disconnect() {
        clientEventJob?.cancel()
        clientEventJob = null
        connectionStateJob?.cancel()
        connectionStateJob = null

        audioRecorder.stopRecording()
        audioTrackPlayer.stopAndFlush()
        liveClient?.disconnect()
        liveClient = null

        _uiState.update {
            it.copy(
                connectionState = LiveConnectionState.DISCONNECTED,
                isGeminiSpeaking = false,
                userMicRms = 0f,
                geminiSpeakerRms = 0f
            )
        }
        addLog("Session disconnected.")
    }

    fun clearTranscript() {
        _uiState.update { it.copy(transcript = "") }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun openSettings() {
        _uiState.update { it.copy(isSettingsOpen = true) }
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsOpen = false) }
    }

    fun saveSettings(
        apiKey: String,
        model: String,
        voice: String,
        hardwareAec: Boolean,
        softwareEchoGate: Boolean,
        systemPrompt: String
    ) {
        settings.apiKey = apiKey
        settings.model = model
        settings.voice = voice
        settings.enableHardwareAec = hardwareAec
        settings.enableSoftwareEchoGate = softwareEchoGate
        settings.systemPrompt = systemPrompt

        audioRecorder.enableHardwareAec = hardwareAec
        audioRecorder.enableSoftwareEchoGate = softwareEchoGate

        _uiState.update {
            it.copy(
                config = settings.getConfig(),
                isSettingsOpen = false,
                errorMessage = null
            )
        }
        addLog("Settings updated. AEC: $hardwareAec, Echo Gate: $softwareEchoGate")
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun addLog(message: String, isBargeIn: Boolean = false, isError: Boolean = false) {
        val entry = LogEntry(
            time = timeFormat.format(Date()),
            message = message,
            isBargeIn = isBargeIn,
            isError = isError
        )
        _uiState.update {
            val list = (listOf(entry) + it.logs).take(100)
            it.copy(logs = list)
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        audioTrackPlayer.release()
    }
}
