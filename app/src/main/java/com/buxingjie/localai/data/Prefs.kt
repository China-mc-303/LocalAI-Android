package com.buxingjie.localai.data

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("localai_prefs", Context.MODE_PRIVATE)

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

    companion object {
        fun defaultThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores - 2).coerceIn(4, 8)
        }
    }
}
