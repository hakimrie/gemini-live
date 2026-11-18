package id.bukuanak.geminilive.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Captures 16kHz 16-bit mono PCM audio from microphone using VOICE_COMMUNICATION audio source.
 * Couples with EchoCancellationManager to activate hardware AEC/NS/AGC and provides
 * an optional Software Echo Gate as an additional safeguard.
 */
class AudioRecorder(
    private val context: Context,
    private val echoCancellationManager: EchoCancellationManager,
    private val onAudioChunkReady: (ByteArray) -> Unit
) {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _micRms = MutableStateFlow(0f)
    val micRms = _micRms.asStateFlow()

    private val _effectsDiagnostics = MutableStateFlow<AudioEffectsDiagnostics?>(null)
    val effectsDiagnostics = _effectsDiagnostics.asStateFlow()

    // Configurable flags
    var isMuted: Boolean = false
    var enableHardwareAec: Boolean = true
    var enableSoftwareEchoGate: Boolean = false
    var isModelSpeaking: Boolean = false
    var echoGateThreshold: Float = 0.08f // RMS threshold for barge-in speech over playback

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (_isRecording.value) return true

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.INPUT_SAMPLE_RATE,
            AudioConfig.INPUT_CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT
        )
        val bufferSize = minBufferSize.coerceAtLeast(AudioConfig.INPUT_CHUNK_BYTES * 2)

        try {
            echoCancellationManager.startCommunicationMode()

            val record = AudioRecord(
                AudioConfig.AUDIO_SOURCE,
                AudioConfig.INPUT_SAMPLE_RATE,
                AudioConfig.INPUT_CHANNEL_CONFIG,
                AudioConfig.AUDIO_FORMAT,
                bufferSize
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed, state: ${record.state}")
                record.release()
                return false
            }

            audioRecord = record

            // Attach hardware AEC, Noise Suppressor, and AGC to the audio session
            val diag = echoCancellationManager.attachEffects(
                audioSessionId = record.audioSessionId,
                enableAec = enableHardwareAec
            )
            _effectsDiagnostics.value = diag

            record.startRecording()
            _isRecording.value = true

            startCaptureLoop()
            Log.d(TAG, "Audio recording started (16kHz mono PCM, sessionId=${record.audioSessionId})")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            stopRecording()
            return false
        }
    }

    private fun startCaptureLoop() {
        recordingJob?.cancel()
        recordingJob = scope.launch {
            val buffer = ByteArray(AudioConfig.INPUT_CHUNK_BYTES)
            var chunks = 0L
            var peakRms = 0f

            while (isActive && _isRecording.value) {
                val record = audioRecord ?: break
                val bytesRead = record.read(buffer, 0, buffer.size)

                if (bytesRead > 0) {
                    val rms = calculateRms(buffer, bytesRead)
                    _micRms.value = rms
                    chunks++
                    peakRms = maxOf(peakRms, rms)
                    if (chunks == 1L || chunks % 50L == 0L) {
                        Log.d(TAG, "Microphone capture: chunks=$chunks, peakRms=$peakRms, muted=$isMuted, echoGate=$enableSoftwareEchoGate, modelSpeaking=$isModelSpeaking")
                        peakRms = 0f
                    }

                    if (isMuted) {
                        // User muted mic: send silence or skip
                        continue
                    }

                    // Software Echo Gate (Defense-in-depth for devices with weak hardware AEC):
                    // When the AI model is outputting audio and software echo gate is on:
                    // Only pass audio if energy exceeds threshold (user is barging in!).
                    if (enableSoftwareEchoGate && isModelSpeaking && rms < echoGateThreshold) {
                        // Suppress background speaker echo
                        continue
                    }

                    val chunk = buffer.copyOf(bytesRead)
                    onAudioChunkReady(chunk)
                } else if (bytesRead < 0) {
                    Log.e(TAG, "Microphone read failed: $bytesRead")
                    break
                }
            }
        }
    }

    private fun calculateRms(pcmBytes: ByteArray, length: Int): Float {
        val sampleCount = length / 2
        if (sampleCount == 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until sampleCount) {
            val sample = ((pcmBytes[i * 2 + 1].toInt() shl 8) or (pcmBytes[i * 2].toInt() and 0xFF)).toShort()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / sampleCount)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    fun stopRecording() {
        _isRecording.value = false
        _micRms.value = 0f
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }

        echoCancellationManager.stopCommunicationMode()
        Log.d(TAG, "Audio recording stopped and hardware effects released")
    }

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
