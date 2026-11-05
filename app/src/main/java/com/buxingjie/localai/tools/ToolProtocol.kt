package com.buxingjie.localai.tools

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ToolCall(
    val name: String,
    val args: JsonObject,
    val raw: String,
)

/**
 * 统一的工具调用协议，本地模型与所有在线模型共用。
 * 模型只需输出一行 JSON：{"tool":"fetch_url","url":"https://…"}
 */
object ToolProtocol {

    val PROMPT = """
你可以使用以下工具来完成用户的请求。需要工具时，只输出一行 JSON（不要加解释、不要用代码块），然后等待工具结果：
{"tool":"fetch_url","url":"https://example.com"}
{"tool":"open_app","app":"微信"}
{"tool":"screen_text"}
{"tool":"tap","x":0.50,"y":0.82}
{"tool":"type","text":"要输入的文字"}
{"tool":"swipe","x1":0.5,"y1":0.8,"x2":0.5,"y2":0.3}
{"tool":"back"}
{"tool":"home"}

规则：
1. 坐标 x/y 都是 0~1 的相对值，先调用 screen_text 查看屏幕上各元素的坐标和文字，再决定点击哪里。
2. 查看网页用 fetch_url；用户给出网址时也可以直接抓取。
3. 一次只调用一个工具，拿到结果后再继续，最多 4 轮。
4. 不需要工具时，直接正常回答，不要输出 JSON。
5. 用户可能随时紧急停止手机操作，被停止后立即停止调用工具。
""".trim()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val phoneTools = setOf("tap", "swipe", "type", "back", "home", "open_app", "screen_text")

    fun parse(text: String): ToolCall? {
        var index = 0
        while (index < text.length) {
            val start = text.indexOf('{', index)
            if (start < 0) return null
            val end = matchEnd(text, start)
            if (end == null) {
                index = start + 1
                continue
            }
            val raw = text.substring(start, end)
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            val name = obj?.get("tool")?.jsonPrimitive?.contentOrNull?.trim()
            if (!name.isNullOrBlank()) {
                val toolName = normalize(name)
                if (toolName in phoneTools || toolName == "fetch_url") {
                    return ToolCall(toolName, obj!!, raw)
                }
            }
            index = end
        }
        return null
    }

    private fun normalize(name: String): String = when (name.lowercase()) {
        "fetch_url", "fetchurl", "web", "browse", "read_url", "open_url" -> "fetch_url"
        "open_app", "openapp", "launch_app", "start_app" -> "open_app"
        "screen_text", "screentext", "read_screen", "screen" -> "screen_text"
        "tap", "click" -> "tap"
        "type", "input", "set_text", "enter_text" -> "type"
        "swipe", "scroll" -> "swipe"
        "back" -> "back"
        "home" -> "home"
        else -> name
    }

    private fun matchEnd(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        return null
    }

    fun describe(call: ToolCall): String = when (call.name) {
        "fetch_url" -> "查看网页 ${call.args.str("url").take(60)}"
        "open_app" -> "打开应用「${call.args.str("app")}」"
        "screen_text" -> "读取屏幕内容"
        "tap" -> "点击屏幕 (${call.args.str("x")}, ${call.args.str("y")})"
        "type" -> "输入文字"
        "swipe" -> "滑动屏幕"
        "back" -> "按返回键"
        "home" -> "回到桌面"
        else -> call.name
    }

    /** 把展示文本里的工具 JSON 去掉。 */
    fun strip(text: String, call: ToolCall?): String {
        if (call == null) return text.trim()
        var out = text.replace(call.raw, "")
        out = out.replace(Regex("```(json|JSON)?\\s*```"), "")
        out = out.replace("<tool>", "").replace("</tool>", "")
        return out.trim().trim('`').trim()
    }

    suspend fun execute(
        context: Context,
        call: ToolCall,
        webEnabled: Boolean,
        phoneEnabled: Boolean,
    ): String {
        if (call.name == "fetch_url") {
            if (!webEnabled) return "联网查看网页已在设置中关闭"
            val url = call.args.str("url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "网址必须以 http:// 或 https:// 开头"
            }
            return runCatching { WebFetcher.fetch(url) }
                .getOrElse { "网页读取失败：${it.message ?: "未知错误"}" }
        }
        if (call.name in phoneTools) {
            if (!phoneEnabled) return "手机操控已在设置中关闭"
            if (PhoneControl.aborted) return "操作已被用户紧急停止"
            return PhoneControl.run(context, call.args)
        }
        return "未知工具：${call.name}"
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
}
