package com.buxingjie.localai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class PublicMessage(
    val name: String,
    val text: String,
    val time: Long,
)

/**
 * 公共聊天室：基于 ntfy.sh 公共主题收发消息（无需登录，所有装了本应用的人可见）。
 * 发送：POST 一段 JSON 到主题；读取：GET ?json&poll=1 拿到 NDJSON。
 */
object PublicChat {

    private const val TOPIC = "localai-public-square-bxj303"
    private const val BASE = "https://ntfy.sh/$TOPIC"
    private const val MAX_TEXT = 300
    private const val MAX_MESSAGES = 200

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): List<PublicMessage> = withContext(Dispatchers.IO) {
        val conn = (URL("$BASE/json?poll=1&since=24h").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", "LocalAI-Android")
        }
        try {
            if (conn.responseCode != 200) return@withContext emptyList()
            val lines = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
            lines.mapNotNull { line ->
                val outer = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                    ?: return@mapNotNull null
                if (outer["event"]?.jsonPrimitive?.content != "message") return@mapNotNull null
                val raw = outer["message"]?.jsonPrimitive?.content ?: return@mapNotNull null
                runCatching { json.decodeFromString<PublicMessage>(raw) }
                    .getOrNull()
                    ?.takeIf { it.text.isNotBlank() }
            }.sortedBy { it.time }.takeLast(MAX_MESSAGES)
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    suspend fun send(name: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val clean = text.trim().take(MAX_TEXT)
        if (clean.isEmpty()) return@withContext false
        val sender = name.trim().ifBlank { "匿名" }.take(16)
        val body = json.encodeToString(
            PublicMessage.serializer(),
            PublicMessage(sender, clean, System.currentTimeMillis()),
        )
        val conn = (URL(BASE).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("User-Agent", "LocalAI-Android")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }
}
