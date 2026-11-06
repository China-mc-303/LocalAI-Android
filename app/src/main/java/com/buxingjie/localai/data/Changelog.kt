package com.buxingjie.localai.data

/**
 * 内置更新说明，网络不可用时也能查看本版本改了什么。
 */
object Changelog {

    data class Version(val version: String, val date: String, val items: List<String>)

    val CURRENT = Version(
        version = "3.5.0",
        date = "2026-11-06",
        items = listOf(
            "修复混元、元宝等模型下载时进度条完全不动的问题，下载速度与剩余时间更直观",
            "模型下载增加断点续传与多线路自动切换，失败后从已下载位置继续，不再从头开始",
            "设置页新增“测试连接”，在线模型会真实发一次请求，直接告诉你是成功还是失败",
            "在线模型回答时顶栏会显示正在使用的平台与模型名，避免出现“以为在用模型其实没连上”",
            "新增 54 个可离线下载的 GGUF 模型，包含混元、DeepSeek、Qwen、Llama、Gemma、GLM、Yi、Phi、Mistral 等系列",
            "AI 可以生成文件并交付给你：写文档、存代码后，在对话页直接打开或分享",
            "终端升级为电脑命令行风格，支持 cd 保持目录、命令历史、快捷命令和状态提示",
            "支持 Shizuku 授权，授权后终端与 AI 的命令以 shell 权限运行，可访问更多系统目录",
            "AI 操控手机新增按文字点击与等待，点外卖、比价、浏览网页时更准确，不再只靠坐标猜",
            "设置页新增“更新说明”按钮，随时查看当前版本改了什么",
            "各 AI 终端历史独立保存，切换平台不会混在一起",
            "修复下载记录与模型文件状态不同步的问题",
        ),
    )

    /** GitHub 接口没拿到说明时，作为兜底展示。 */
    fun currentText(): String = buildString {
        appendLine("v${CURRENT.version}（${CURRENT.date}）")
        CURRENT.items.forEachIndexed { index, item ->
            appendLine("${index + 1}. $item")
        }
    }
}
