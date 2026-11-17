package id.bukuanak.geminilive.audio

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

data class AudioEffectsDiagnostics(
    val aecAvailable: Boolean,
    var aecEnabled: Boolean = false,
    val nsAvailable: Boolean,
    var nsEnabled: Boolean = false,
    val agcAvailable: Boolean,
    var agcEnabled: Boolean = false,
    var currentMode: String = "NORMAL"
)

/**
 * Manages Acoustic Echo Cancellation (AEC), Noise Suppression (NS), Automatic Gain Control (AGC),
 * and AudioManager's communication mode for bidirectional Gemini voice conversations.
 */
class EchoCancellationManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    val isAecAvailable: Boolean
        get() = AcousticEchoCanceler.isAvailable()

    val isNsAvailable: Boolean
        get() = NoiseSuppressor.isAvailable()

    val isAgcAvailable: Boolean
        get() = AutomaticGainControl.isAvailable()

    /**
     * Prepares Android audio system for bidirectional voice communication with AEC.
     * MODE_IN_COMMUNICATION informs the hardware audio HAL to route mic and speaker
     * through the echo-cancellation DSP pipeline.
     */
    fun startCommunicationMode() {
        try {
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val speakerDevice = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
            Log.d(TAG, "Audio mode set to MODE_IN_COMMUNICATION with speaker routing")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set audio communication mode", e)
        }
    }

    /**
     * Attaches hardware audio effects to the AudioRecord session.
     */
    fun attachEffects(audioSessionId: Int, enableAec: Boolean = true, enableNs: Boolean = true, enableAgc: Boolean = true): AudioEffectsDiagnostics {
        releaseEffects()

        val diag = AudioEffectsDiagnostics(
            aecAvailable = isAecAvailable,
            nsAvailable = isNsAvailable,
            agcAvailable = isAgcAvailable,
            currentMode = "MODE_IN_COMMUNICATION"
        )

        if (audioSessionId == 0) {
            Log.w(TAG, "Invalid audioSessionId: 0, cannot attach hardware audio effects")
            return diag
        }

        if (isAecAvailable && enableAec) {
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "Hardware AcousticEchoCanceler enabled on session $audioSessionId")
                }
                diag.aecEnabled = echoCanceler?.enabled == true
            } catch (e: Exception) {
                Log.e(TAG, "Error enabling AcousticEchoCanceler", e)
            }
        }

        if (isNsAvailable && enableNs) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "Hardware NoiseSuppressor enabled on session $audioSessionId")
                }
                diag.nsEnabled = noiseSuppressor?.enabled == true
            } catch (e: Exception) {
                Log.e(TAG, "Error enabling NoiseSuppressor", e)
            }
        }

        if (isAgcAvailable && enableAgc) {
            try {
                gainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "Hardware AutomaticGainControl enabled on session $audioSessionId")
                }
                diag.agcEnabled = gainControl?.enabled == true
            } catch (e: Exception) {
                Log.e(TAG, "Error enabling AutomaticGainControl", e)
            }
        }

        return diag
    }

    /**
     * Releases active audio effects.
     */
    fun releaseEffects() {
        try {
            echoCanceler?.release()
            echoCanceler = null
            noiseSuppressor?.release()
            noiseSuppressor = null
            gainControl?.release()
            gainControl = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects", e)
        }
    }

    /**
     * Restores previous audio manager mode.
     */
    fun stopCommunicationMode() {
        releaseEffects()
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = previousAudioMode
            Log.d(TAG, "Restored previous audio mode: $previousAudioMode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore audio mode", e)
        }
    }

    companion object {
        private const val TAG = "EchoCancelManager"
    }
}
