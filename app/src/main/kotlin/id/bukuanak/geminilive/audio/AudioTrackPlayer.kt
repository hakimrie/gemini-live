package id.bukuanak.geminilive.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Handles real-time streaming audio playback for Gemini's 24kHz PCM audio responses.
 * Specifically built with USAGE_VOICE_COMMUNICATION for hardware AEC reference alignment,
 * and instant queue flushing for low-latency barge-in interruption.
 */
class AudioTrackPlayer {

    private var audioTrack: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _outputRms = MutableStateFlow(0f)
    val outputRms = _outputRms.asStateFlow()

    @Volatile
    private var isFlushing = false

    fun initAudioTrack() {
        if (audioTrack != null) return

        val minBufferSize = AudioTrack.getMinBufferSize(
            AudioConfig.OUTPUT_SAMPLE_RATE,
            AudioConfig.OUTPUT_CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT
        )
        val bufferSize = minBufferSize.coerceAtLeast(AudioConfig.OUTPUT_SAMPLE_RATE * 2) // ~1 sec buffer

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(AudioConfig.OUTPUT_SAMPLE_RATE)
            .setChannelMask(AudioConfig.OUTPUT_CHANNEL_CONFIG)
            .setEncoding(AudioConfig.AUDIO_FORMAT)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        startPlaybackWorker()
        Log.d(TAG, "AudioTrack initialized: 24kHz mono PCM, USAGE_VOICE_COMMUNICATION")
    }

    private fun startPlaybackWorker() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val shortArray = ShortArray(2048)
            while (isActive) {
                val chunk = audioQueue.poll(50, TimeUnit.MILLISECONDS)
                if (chunk != null && !isFlushing) {
                    _isPlaying.value = true
                    calculateRms(chunk, shortArray)
                    audioTrack?.write(chunk, 0, chunk.size)
                } else if (audioQueue.isEmpty()) {
                    _isPlaying.value = false
                    _outputRms.value = 0f
                }
            }
        }
    }

    /**
     * Enqueue a chunk of 24kHz PCM audio received from Gemini.
     */
    fun enqueueAudio(data: ByteArray) {
        if (!isFlushing && data.isNotEmpty()) {
            audioQueue.offer(data)
        }
    }

    /**
     * INSTANT BARGE-IN:
     * Immediately stops current playback, clears all queued audio packets,
     * and flushes the hardware AudioTrack buffer.
     */
    fun stopAndFlush() {
        isFlushing = true
        try {
            audioQueue.clear()
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
            _isPlaying.value = false
            _outputRms.value = 0f
            Log.d(TAG, "AudioTrack FLUSHED on barge-in / interruption")
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack", e)
        } finally {
            isFlushing = false
        }
    }

    private fun calculateRms(pcmBytes: ByteArray, shortBuffer: ShortArray) {
        val sampleCount = (pcmBytes.size / 2).coerceAtMost(shortBuffer.size)
        var sumSquares = 0.0
        for (i in 0 until sampleCount) {
            val sample = ((pcmBytes[i * 2 + 1].toInt() shl 8) or (pcmBytes[i * 2].toInt() and 0xFF)).toShort()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / sampleCount)
        val normalized = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
        _outputRms.value = normalized
    }

    fun release() {
        playbackJob?.cancel()
        stopAndFlush()
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        }
    }

    companion object {
        private const val TAG = "AudioTrackPlayer"
    }
}
