package com.buxingjie.localai.data

/**
 * 在线大模型提供商。全部使用 OpenAI 兼容协议，因此共用同一个客户端。
 */
enum class AiProvider(
    val id: String,
    val label: String,
    val vendor: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val modelHint: String,
    val keyHint: String,
) {
    DEEPSEEK(
        id = "deepseek",
        label = "DeepSeek",
        vendor = "深度求索",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        modelHint = "deepseek-chat / deepseek-reasoner",
        keyHint = "sk-…（platform.deepseek.com 获取）",
    ),
    KIMI(
        id = "kimi",
        label = "Kimi",
        vendor = "月之暗面",
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "kimi-latest",
        modelHint = "kimi-latest / moonshot-v1-8k",
        keyHint = "sk-…（platform.moonshot.cn 获取）",
    ),
    DOUBAO(
        id = "doubao",
        label = "豆包",
        vendor = "火山方舟",
        defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        defaultModel = "doubao-1.5-pro-32k-250115",
        modelHint = "推理接入点 ep-xxxx 或模型名",
        keyHint = "方舟 API Key（console.volcengine.com）",
    ),
    YUANBAO(
        id = "yuanbao",
        label = "元宝",
        vendor = "腾讯混元",
        defaultBaseUrl = "https://api.hunyuan.cloud.tencent.com/v1",
        defaultModel = "hunyuan-turbos-latest",
        modelHint = "hunyuan-turbos-latest / hunyuan-lite",
        keyHint = "腾讯云 API Key（hunyuan.cloud.tencent.com）",
    ),
    GPT(
        id = "gpt",
        label = "GPT（可选）",
        vendor = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        modelHint = "gpt-4o-mini / gpt-4o",
        keyHint = "sk-…（需要可访问 OpenAI 的网络）",
    ),
    CUSTOM(
        id = "custom",
        label = "自定义（OpenAI 兼容）",
        vendor = "第三方 / 自建",
        defaultBaseUrl = "",
        defaultModel = "",
        modelHint = "填写服务端提供的模型名",
        keyHint = "填写该服务的 API Key（可留空）",
    ),
    ;

    companion object {
        fun fromId(id: String?): AiProvider? = entries.firstOrNull { it.id == id }
    }
}

data class RemoteEngineConfig(
    val provider: AiProvider,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    val ready: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}
