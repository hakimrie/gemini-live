package id.bukuanak.geminilive.audio

import android.media.AudioFormat
import android.media.MediaRecorder

object AudioConfig {
    // Gemini Live API expects 16kHz 16-bit mono PCM input
    const val INPUT_SAMPLE_RATE = 16000
    const val INPUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    
    // VOICE_COMMUNICATION engages Android's hardware AEC and VoIP DSP paths
    const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_COMMUNICATION

    // 100ms chunk: 16000 samples/sec * 0.1s = 1600 samples * 2 bytes/sample = 3200 bytes
    const val INPUT_CHUNK_BYTES = 3200

    // Gemini Live API returns 24kHz 16-bit mono PCM output
    const val OUTPUT_SAMPLE_RATE = 24000
    const val OUTPUT_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO

    const val MIME_TYPE_PCM_16KHZ = "audio/pcm;rate=16000"
    const val MIME_TYPE_PCM_24KHZ = "audio/pcm;rate=24000"
}
