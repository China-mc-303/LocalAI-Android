package com.buxingjie.localai.tools

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

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
{"tool":"open_url","url":"https://example.com"}
{"tool":"shell","cmd":"ls -la"}
{"tool":"write_file","name":"报告.md","content":"文件的完整内容"}
{"tool":"open_app","app":"微信"}
{"tool":"screen_text"}
{"tool":"tap","x":0.50,"y":0.82}
{"tool":"tap_text","text":"加入购物车"}
{"tool":"type","text":"要输入的文字"}
{"tool":"swipe","x1":0.5,"y1":0.8,"x2":0.5,"y2":0.3}
{"tool":"wait","seconds":2}
{"tool":"back"}
{"tool":"home"}

规则：
1. 坐标 x/y 都是 0~1 的相对值。优先用 tap_text 按文字点击，只有找不到文字时才用 tap 坐标。
2. 查看网页用 fetch_url；用户给出网址时也可以直接抓取。想在手机浏览器里打开网页给用户看时，用 open_url。
3. shell 命令在用户设置的“工作目录”中执行，cd 会保持目录；输出会显示在终端里。
4. 用户要求写文档、保存代码、导出内容时，用 write_file 生成文件。文件会保存到工作目录，并在对话页提供打开和分享按钮。
5. 一次只调用一个工具，拿到结果后再继续，最多 4 轮。
6. 不需要工具时，直接正常回答，不要输出 JSON。
7. 用户可能随时紧急停止手机操作，被停止后立即停止调用工具。
8. 打开应用时如果返回“没有找到应用”，必须如实告诉用户该应用未安装，不要编造已打开。
9. 点外卖、比价、购物这类任务：先 open_app 打开对应应用，再 screen_text 看屏幕，用 tap_text 搜索或进入商品页，用 type 输入关键词，用 swipe 翻页，找到合适的商品后把名称和价格告诉用户，付款前必须让用户确认。
10. 不要在未确认的情况下替用户付款、下单或发送消息。
""".trim()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val phoneTools = setOf(
        "tap", "tap_text", "swipe", "type", "back", "home", "open_app", "screen_text"
    )

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
                if (toolName in phoneTools || toolName in fileTools ||
                    toolName == "fetch_url" || toolName == "open_url" ||
                    toolName == "shell" || toolName == "wait"
                ) {
                    return ToolCall(toolName, obj!!, raw)
                }
            }
            index = end
        }
        return null
    }

    private fun normalize(name: String): String = when (name.lowercase()) {
        "fetch_url", "fetchurl", "web", "browse", "read_url", "open_url" -> if (name.lowercase().contains("open")) "open_url" else "fetch_url"
        "shell", "bash", "terminal", "exec", "run_command", "cmd" -> "shell"
        "write_file", "writefile", "save_file", "create_file", "save", "write" -> "write_file"
        "open_app", "openapp", "launch_app", "start_app" -> "open_app"
        "screen_text", "screentext", "read_screen", "screen" -> "screen_text"
        "tap", "click" -> "tap"
        "tap_text", "click_text", "taptext" -> "tap_text"
        "type", "input", "set_text", "enter_text" -> "type"
        "swipe", "scroll" -> "swipe"
        "wait", "sleep" -> "wait"
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

    private val fileTools = setOf("write_file")

    fun describe(call: ToolCall): String = when (call.name) {
        "fetch_url" -> "查看网页 ${call.args.str("url").take(60)}"
        "open_url" -> "在浏览器打开 ${call.args.str("url").take(60)}"
        "shell" -> "执行终端命令 ${call.args.str("cmd").take(60)}"
        "write_file" -> "生成文件「${call.args.str("name")}」"
        "open_app" -> "打开应用「${call.args.str("app")}」"
        "screen_text" -> "读取屏幕内容"
        "tap" -> "点击屏幕 (${call.args.str("x")}, ${call.args.str("y")})"
        "tap_text" -> "点击「${call.args.str("text")}」"
        "type" -> "输入文字"
        "swipe" -> "滑动屏幕"
        "wait" -> "等待 ${call.args.str("seconds")} 秒"
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
        workDir: String,
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
        if (call.name == "write_file") {
            return writeFile(call, workDir)
        }
        if (call.name == "open_url") {
            if (!webEnabled) return "联网打开网页已在设置中关闭"
            val url = call.args.str("url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return "网址必须以 http:// 或 https:// 开头"
            }
            return runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "已在手机浏览器中打开：$url\n接下来可以调用 screen_text 查看页面，再用 tap_text 点击链接。"
            }.getOrElse { "打开浏览器失败：${it.message ?: "没有可用的浏览器"}" }
        }
        if (call.name == "wait") {
            val seconds = call.args.str("seconds").toFloatOrNull()?.coerceIn(0.2f, 15f) ?: 1.5f
            kotlinx.coroutines.delay((seconds * 1000).toLong())
            return "已等待 ${"%.1f".format(seconds)} 秒"
        }
        if (call.name in phoneTools) {
            if (!phoneEnabled) return "手机操控已在设置中关闭"
            if (PhoneControl.aborted) return "操作已被用户紧急停止"
            return PhoneControl.run(context, call.args)
        }
        return "未知工具：${call.name}"
    }

    /** 把模型生成的内容写入工作目录，路径不能逃逸到工作目录之外。 */
    private fun writeFile(call: ToolCall, workDir: String): String {
        val rawName = call.args.str("name").ifBlank { call.args.str("path") }.ifBlank { "生成文件.txt" }
        val safe = rawName.replace('\\', '/').trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
        if (safe.isBlank()) return "文件名不合法"
        val base = File(workDir)
        val target = File(base, safe)
        if (!target.canonicalPath.startsWith(base.canonicalPath)) return "文件名不合法"
        val content = call.args.str("content")
        if (content.isBlank()) return "文件内容为空，没有写入"
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
            val size = target.length()
            val pretty = when {
                size < 1024 -> "$size 字节"
                size < 1024 * 1024 -> "${size / 1024} KB"
                else -> "${size / 1024 / 1024} MB"
            }
            "已生成文件：${target.name}\n保存位置：${target.absolutePath}\n大小：$pretty\n文件已保存到工作目录，用户可以在对话页打开或分享。"
        }.getOrElse { "写入文件失败：${it.message ?: "未知错误"}" }
    }

    fun arg(call: ToolCall, key: String): String = call.args.str(key)

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
}
