package tw.transcribe.app

import android.content.Context

class AppSettings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = p.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) = p.edit().putString("baseUrl", v.trim()).apply()

    var apiKey: String
        get() = p.getString("apiKey", "") ?: ""
        set(v) = p.edit().putString("apiKey", v.trim()).apply()

    var model: String
        get() = p.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = p.edit().putString("model", v.trim()).apply()

    var prompt: String
        get() = p.getString("prompt", DEFAULT_PROMPT) ?: DEFAULT_PROMPT
        set(v) = p.edit().putString("prompt", v).apply()

    var chunkMinutes: Int
        get() = p.getInt("chunkMinutes", 10)
        set(v) = p.edit().putInt("chunkMinutes", v.coerceIn(1, 12)).apply()

    var showTimestamps: Boolean
        get() = p.getBoolean("showTimestamps", true)
        set(v) = p.edit().putBoolean("showTimestamps", v).apply()

    var preferOffline: Boolean
        get() = p.getBoolean("preferOffline", false)
        set(v) = p.edit().putBoolean("preferOffline", v).apply()

    var muteBeep: Boolean
        get() = p.getBoolean("muteBeep", true)
        set(v) = p.edit().putBoolean("muteBeep", v).apply()

    companion object {
        const val DEFAULT_BASE = "https://api.groq.com/openai/v1"
        const val DEFAULT_MODEL = "whisper-large-v3"
        const val DEFAULT_PROMPT = "以下是一段台灣的繁體中文逐字稿，請使用繁體中文與全形標點符號。"
    }
}
