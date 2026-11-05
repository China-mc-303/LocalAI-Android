package com.buxingjie.localai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class StoredMessage(
    val role: String,
    val content: String,
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<StoredMessage> = emptyList(),
)

class ChatStore(context: Context) {

    private val dir = File(context.filesDir, "chats").apply { mkdirs() }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun newSession(): ChatSession =
        ChatSession(UUID.randomUUID().toString(), "新对话", System.currentTimeMillis())

    fun save(session: ChatSession) {
        runCatching {
            File(dir, "${session.id}.json").writeText(json.encodeToString(ChatSession.serializer(), session))
        }
    }

    fun load(id: String): ChatSession? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(ChatSession.serializer(), file.readText()) }.getOrNull()
    }

    fun latest(): ChatSession? {
        val files = dir.listFiles()?.filter { it.isFile && it.extension == "json" } ?: return null
        if (files.isEmpty()) return null
        return files.maxByOrNull { it.lastModified() }?.let { file ->
            runCatching { json.decodeFromString(ChatSession.serializer(), file.readText()) }.getOrNull()
        }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }
}
