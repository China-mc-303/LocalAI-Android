package com.buxingjie.localai.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("localai_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var modelPath: String?
        get() = sp.getString("model_path", null)
        set(value) = sp.edit().putString("model_path", value).apply()

    var temperature: Float
        get() = sp.getFloat("temperature", 0.7f)
        set(value) = sp.edit().putFloat("temperature", value).apply()

    var maxTokens: Int
        get() = sp.getInt("max_tokens", 512)
        set(value) = sp.edit().putInt("max_tokens", value).apply()

    var contextSize: Int
        get() = sp.getInt("context_size", 8192)
        set(value) = sp.edit().putInt("context_size", value).apply()

    var threads: Int
        get() = sp.getInt("threads", defaultThreads())
        set(value) = sp.edit().putInt("threads", value).apply()

    var workMode: Boolean
        get() = sp.getBoolean("work_mode", false)
        set(value) = sp.edit().putBoolean("work_mode", value).apply()

    var lastSessionId: String?
        get() = sp.getString("last_session", null)
        set(value) = sp.edit().putString("last_session", value).apply()

    /** AI 终端与命令的工作目录 */
    var workingDir: String?
        get() = sp.getString("working_dir", null)
        set(value) = sp.edit().putString("working_dir", value).apply()

    var downloadHistory: List<DownloadRecord>
        get() = runCatching {
            json.decodeFromString<List<DownloadRecord>>(sp.getString("download_history", "[]") ?: "[]")
        }.getOrDefault(emptyList())
        set(value) = sp.edit()
            .putString("download_history", json.encodeToString(value.takeLast(50)))
            .apply()

    /** 当前使用的引擎：local 或 AiProvider.id */
    var engine: String
        get() = sp.getString("engine", "local") ?: "local"
        set(value) = sp.edit().putString("engine", value).apply()

    var webToolsEnabled: Boolean
        get() = sp.getBoolean("tool_web", true)
        set(value) = sp.edit().putBoolean("tool_web", value).apply()

    var phoneToolsEnabled: Boolean
        get() = sp.getBoolean("tool_phone", true)
        set(value) = sp.edit().putBoolean("tool_phone", value).apply()

    var primaryColor: Long
        get() = sp.getLong("theme_primary", 0xFF2DD4BF)
        set(value) = sp.edit().putLong("theme_primary", value).apply()

    var accentColor: Long
        get() = sp.getLong("theme_accent", 0xFF7DD3FC)
        set(value) = sp.edit().putLong("theme_accent", value).apply()

    var glassOpacity: Float
        get() = sp.getFloat("glass_opacity", 0.16f)
        set(value) = sp.edit().putFloat("glass_opacity", value).apply()

    var wallpaperPath: String?
        get() = sp.getString("wallpaper_path", null)
        set(value) = sp.edit().putString("wallpaper_path", value).apply()

    var wallpaperBlur: Int
        get() = sp.getInt("wallpaper_blur", 0)
        set(value) = sp.edit().putInt("wallpaper_blur", value).apply()

    /** 主题模式：dark / light */
    var themeMode: String
        get() = sp.getString("theme_mode", "dark") ?: "dark"
        set(value) = sp.edit().putString("theme_mode", value).apply()

    fun apiKey(provider: AiProvider): String =
        sp.getString("p_${provider.id}_key", "") ?: ""

    fun setApiKey(provider: AiProvider, value: String) =
        sp.edit().putString("p_${provider.id}_key", value.trim()).apply()

    fun baseUrl(provider: AiProvider): String {
        val saved = sp.getString("p_${provider.id}_base", null)
        return if (saved.isNullOrBlank()) provider.defaultBaseUrl else saved
    }

    fun setBaseUrl(provider: AiProvider, value: String) =
        sp.edit().putString("p_${provider.id}_base", value.trim()).apply()

    fun modelName(provider: AiProvider): String {
        val saved = sp.getString("p_${provider.id}_model", null)
        return if (saved.isNullOrBlank()) provider.defaultModel else saved
    }

    fun setModelName(provider: AiProvider, value: String) =
        sp.edit().putString("p_${provider.id}_model", value.trim()).apply()

    fun config(provider: AiProvider): RemoteEngineConfig =
        RemoteEngineConfig(provider, baseUrl(provider), modelName(provider), apiKey(provider))

    companion object {
        fun defaultThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores - 2).coerceIn(4, 8)
        }
    }
}
