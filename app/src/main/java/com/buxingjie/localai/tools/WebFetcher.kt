package com.buxingjie.localai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 轻量网页抓取：不引入第三方 HTML 库，直接去标签转纯文本。
 */
object WebFetcher {

    private val URL_REGEX = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", RegexOption.IGNORE_CASE)
    private const val MAX_BYTES = 300_000
    private const val MAX_CHARS = 6000

    fun firstUrl(text: String): String? =
        URL_REGEX.find(text)?.value?.trimEnd('.', ',', '。', '，', ')', '）', '"', '\'')

    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; LocalAI/2.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain,*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val type = connection.contentType ?: ""
            if (type.contains("application/pdf", true) || type.contains("application/octet-stream", true)) {
                throw IOException("该链接不是网页（${type.ifBlank { "二进制文件" }}），无法直接阅读")
            }
            val charset = charsetOf(type) ?: "UTF-8"
            val bytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    total += read
                    if (total >= MAX_BYTES) break
                }
                out.toByteArray()
            }
            val raw = bytes.toString(Charset.forName(charset))
            if (type.contains("text/html", true) || raw.contains("<html", true) || raw.contains("<body", true)) {
                htmlToText(raw)
            } else {
                raw.trim().take(MAX_CHARS)
            }
        } finally {
            connection.disconnect()
        }
    }

    fun htmlToText(html: String): String {
        var text = html
        text = text.replace(Regex("""<!--[\s\S]*?-->"""), " ")
        text = text.replace(Regex("""<script[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("""<style[^>]*>[\s\S]*?</style>""", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("""<head[^>]*>[\s\S]*?</head>""", RegexOption.IGNORE_CASE), " ")
        val title = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.let(::decodeEntities)?.trim()
        text = text.replace(Regex("<(br|hr)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("</(p|div|li|tr|h[1-6]|section|article)>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = decodeEntities(text)
        text = text.replace('\u00A0', ' ')
        text = text.lines().map { it.trim().replace(Regex("[ \\t]{2,}"), " ") }.filter { it.isNotEmpty() }
            .joinToString("\n")
        text = text.replace(Regex("\n{3,}"), "\n\n")
        val body = text.take(MAX_CHARS)
        return if (title.isNullOrBlank() || body.startsWith(title)) body else "标题：$title\n\n$body"
    }

    private fun charsetOf(contentType: String): String? =
        Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE).find(contentType)
            ?.groupValues?.getOrNull(1)?.trim('"', '\'')

    private fun decodeEntities(text: String): String {
        var result = text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
        result = Regex("&#x([0-9a-fA-F]+);").replace(result) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0xFFFF) code.toChar().toString() else match.value
        }
        result = Regex("&#(\\d+);").replace(result) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0xFFFF) code.toChar().toString() else match.value
        }
        return result
    }
}
