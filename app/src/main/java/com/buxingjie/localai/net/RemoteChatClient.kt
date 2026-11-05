package com.buxingjie.localai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ChatMsg(val role: String, val content: String)

data class RemoteRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<ChatMsg>,
    val temperature: Float,
    val maxTokens: Int,
)

/**
 * OpenAI 兼容的流式对话客户端（DeepSeek、Kimi、豆包、元宝、GPT、自定义均可）。
 * 使用 HttpURLConnection，避免额外依赖。
 */
class RemoteChatClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var connection: HttpURLConnection? = null

    fun cancel() {
        runCatching { connection?.disconnect() }
    }

    suspend fun stream(request: RemoteRequest, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val url = endpoint(request.baseUrl)
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream, application/json")
                setRequestProperty("User-Agent", "LocalAI/2.0 (Android)")
                if (request.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${request.apiKey}")
                }
            }
            connection = conn
            try {
                conn.outputStream.use { out ->
                    out.write(buildBody(request).toString().toByteArray(Charsets.UTF_8))
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IOException("HTTP $code：${readError(conn)}")
                }

                val contentType = conn.contentType ?: ""
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8)

                if (contentType.contains("text/event-stream", ignoreCase = true) ||
                    !contentType.contains("application/json", ignoreCase = true)
                ) {
                    val text = readSse(body, onDelta)
                    if (text.isNotBlank()) return@withContext text
                }
                // 少数服务忽略 stream=true，直接返回整段 JSON
                val raw = body.readText()
                return@withContext parseWhole(raw).ifBlank {
                    throw IOException("服务返回了空内容，请检查模型名称与接口地址")
                }
            } finally {
                connection = null
                conn.disconnect()
            }
        }

    private suspend fun readSse(reader: BufferedReader, onDelta: (String) -> Unit): String {
        val answer = StringBuilder()
        var raw = StringBuilder()
        try {
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                if (line.startsWith(":")) continue
                if (!line.startsWith("data:")) {
                    raw.append(line).append('\n')
                    continue
                }
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                obj["error"]?.let { throw IOException(errorText(it)) }
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject
                val piece = delta?.get("content")?.jsonPrimitive?.contentOrNull
                    ?: choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                if (!piece.isNullOrEmpty()) {
                    answer.append(piece)
                    onDelta(piece)
                }
            }
        } catch (e: IOException) {
            // 取消（disconnect）会抛出 SocketException；已有内容时按正常结束处理
            if (answer.isEmpty()) throw e
        }
        if (answer.isNotEmpty()) return answer.toString()
        return parseWhole(raw.toString())
    }

    private fun parseWhole(raw: String): String {
        if (raw.isBlank()) return ""
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return ""
        obj["error"]?.let { throw IOException(errorText(it)) }
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return ""
        return choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: ""
    }

    private fun errorText(element: kotlinx.serialization.json.JsonElement): String {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return element.toString().take(300)
        return (obj["message"]?.jsonPrimitive?.contentOrNull ?: obj["type"]?.jsonPrimitive?.contentOrNull
            ?: element.toString()).take(300)
    }

    private fun buildBody(request: RemoteRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        put("temperature", request.temperature)
        put("max_tokens", request.maxTokens)
        putJsonArray("messages") {
            request.messages.forEach { msg ->
                addJsonObject {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            }
        }
    }

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isBlank()) throw IOException("请先填写接口地址（Base URL）")
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun readError(conn: HttpURLConnection): String = runCatching {
        val text = conn.errorStream?.bufferedReader()?.readText().orEmpty()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        val message = obj?.get("error")?.let { errorText(it) }
        (message ?: text).take(300).ifBlank { conn.responseMessage ?: "未知错误" }
    }.getOrDefault("未知错误")
}
