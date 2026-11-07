package com.example.geminilive.data

import android.content.Context
import android.content.SharedPreferences

data class AppConfig(
    val apiKey: String,
    val model: String,
    val voice: String,
    val enableHardwareAec: Boolean,
    val enableSoftwareEchoGate: Boolean,
    val systemPrompt: String
)

class AppSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var voice: String
        get() = prefs.getString(KEY_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    var enableHardwareAec: Boolean
        get() = prefs.getBoolean(KEY_HARDWARE_AEC, true)
        set(value) = prefs.edit().putBoolean(KEY_HARDWARE_AEC, value).apply()

    var enableSoftwareEchoGate: Boolean
        get() = prefs.getBoolean(KEY_SOFTWARE_ECHO_GATE, false)
        set(value) = prefs.edit().putBoolean(KEY_SOFTWARE_ECHO_GATE, value).apply()

    var systemPrompt: String
        get() = prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(value) = prefs.edit().putString(KEY_SYSTEM_PROMPT, value).apply()

    fun getConfig(): AppConfig = AppConfig(
        apiKey = apiKey,
        model = model,
        voice = voice,
        enableHardwareAec = enableHardwareAec,
        enableSoftwareEchoGate = enableSoftwareEchoGate,
        systemPrompt = systemPrompt
    )

    companion object {
        private const val PREFS_NAME = "gemini_live_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_VOICE = "voice"
        private const val KEY_HARDWARE_AEC = "enable_hardware_aec"
        private const val KEY_SOFTWARE_ECHO_GATE = "enable_software_echo_gate"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"

        const val DEFAULT_MODEL = "models/gemini-2.0-flash-exp"
        const val DEFAULT_VOICE = "Puck"
        val AVAILABLE_VOICES = listOf("Puck", "Charon", "Aoede", "Fenrir", "Kore")
        val AVAILABLE_MODELS = listOf(
            "models/gemini-2.0-flash-exp",
            "models/gemini-2.0-flash-realtime-exp"
        )
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful, conversational, and direct voice assistant. Keep answers natural, friendly, and concise."
    }
}
