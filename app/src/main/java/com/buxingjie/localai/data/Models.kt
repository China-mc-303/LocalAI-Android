package com.buxingjie.localai.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class LocalModel(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
) {
    val path: String get() = file.absolutePath
}

data class RemoteModel(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val params: String,
    val description: String,
    val url: String,
    val fallbackUrl: String,
)

@kotlinx.serialization.Serializable
data class DownloadRecord(
    val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val time: Long,
)

class ModelRepository(private val context: Context) {

    val modelsDir: File = File(context.filesDir, "models").apply { mkdirs() }

    fun installed(): List<LocalModel> =
        modelsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.map { file -> LocalModel(file, prettyName(file.name), file.length()) }
            ?: emptyList()

    fun delete(model: LocalModel): Boolean = model.file.delete()

    suspend fun importFromUri(uri: Uri, onProgress: (Long, Long) -> Unit): LocalModel =
        withContext(Dispatchers.IO) {
            val name = queryDisplayName(uri) ?: "model_${System.currentTimeMillis()}.gguf"
            val safeName = sanitize(name)
            val target = File(modelsDir, safeName)
            val temp = File(modelsDir, "$safeName.part")

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output ->
                    val total = querySize(uri)
                    var copied = 0L
                    var lastReport = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (copied - lastReport >= 1024 * 1024) {
                            lastReport = copied
                            onProgress(copied, total)
                        }
                    }
                    onProgress(copied, total)
                }
            } ?: throw IOException("无法读取所选文件")

            verifyGguf(temp)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IOException("保存模型失败")
            LocalModel(target, prettyName(target.name), target.length())
        }

    /** GitHub 加速镜像，模型下载同样生效。 */
    private val ghProxies = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
        "https://github.moeyy.xyz/",
    )

    /** 该地址是否已经带了某个加速镜像前缀。 */
    private fun alreadyProxied(url: String): Boolean =
        ghProxies.any { url.startsWith(it) } ||
            Regex("^https?://[^/]*(gh-proxy|ghfast|ghproxy|llkk|moeyy)[^/]*/")
                .containsMatchIn(url)

    /** 为每个原始地址生成候选列表：GitHub 地址优先走镜像，最后回退直连；已带前缀的不再重复拼接。 */
    private fun candidatesOf(model: RemoteModel): List<String> {
        val out = LinkedHashSet<String>()
        for (url in listOf(model.url, model.fallbackUrl)) {
            if (url.isBlank()) continue
            if ("github.com" in url && !alreadyProxied(url)) {
                ghProxies.forEach { out += it + url }
            }
            out += url
        }
        return out.toList()
    }

    suspend fun download(model: RemoteModel, onProgress: (Long, Long) -> Unit): LocalModel =
        withContext(Dispatchers.IO) {
            val target = File(modelsDir, model.fileName)
            if (target.exists() && isValidGguf(target)) {
                return@withContext LocalModel(target, model.name, target.length())
            }
            val temp = File(modelsDir, model.fileName + ".part")

            // 每个下载源最多重试 3 次；断点续传，重试从已下载处继续，不浪费流量
            var lastError: Exception? = null
            val candidates = candidatesOf(model)
            for (url in candidates) {
                repeat(3) { attempt ->
                    try {
                        downloadFrom(url, temp, model.sizeBytes, onProgress)
                        lastError = null
                        verifyGguf(temp)
                        if (target.exists()) target.delete()
                        if (!temp.renameTo(target)) throw IOException("保存模型失败")
                        return@withContext LocalModel(target, model.name, target.length())
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        lastError = e
                    }
                }
            }
            throw lastError ?: IOException("下载失败：所有线路均不可用，请检查网络")
        }

    private suspend fun downloadFrom(
        url: String,
        temp: File,
        expectedSize: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val existing = if (temp.exists()) temp.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LocalAI/1.0 (Android)")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val response = connection.responseCode
            if (response != HttpURLConnection.HTTP_OK && response != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("下载失败，HTTP $response")
            }
            val append = response == HttpURLConnection.HTTP_PARTIAL && existing > 0
            val headerLength = connection.contentLengthLong
            // 加速镜像常用分块传输，contentLength 会是 -1；用模型声明的体积兜底，进度条才能动
            val total = when {
                append && headerLength > 0 -> existing + headerLength
                headerLength > 0 -> headerLength
                expectedSize > 0 -> expectedSize
                else -> -1L
            }
            if (existing > 0) onProgress(existing, total)
            var copied = if (append) existing else 0L

            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReportSize = 0L
                    var lastReportTime = 0L
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        // 每 150 毫秒或每 1MB 汇报一次，慢速时进度条也能持续移动
                        val now = System.currentTimeMillis()
                        if (copied - lastReportSize >= 1024 * 1024 || now - lastReportTime >= 150) {
                            lastReportSize = copied
                            lastReportTime = now
                            onProgress(copied, total)
                        }
                    }
                    onProgress(copied, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidGguf(file: File): Boolean =
        runCatching { verifyGguf(file) }.isSuccess

    private fun verifyGguf(file: File) {
        if (file.length() < 4) throw IOException("文件不完整")
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4) throw IOException("文件为空")
            val text = magic.joinToString("") { it.toInt().toChar().toString() }
            if (text != "GGUF") throw IOException("不是有效的 GGUF 模型文件")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return -1
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.endsWith(".gguf", true)) cleaned else "$cleaned.gguf"
    }

    private fun prettyName(fileName: String): String {
        return fileName.removeSuffix(".gguf")
            .replace("qwen2.5-", "Qwen2.5 ")
            .replace("-instruct", "")
            .replace("-q4_k_m", " · Q4_K_M")
            .replace("-q4_0", " · Q4_0")
    }

    companion object {
        /** 体积统一用 MB 写，代码里换算成字节。 */
        private fun m(
            id: String,
            name: String,
            fileName: String,
            mb: Long,
            params: String,
            description: String,
            url: String,
            fallback: String = "",
        ) = RemoteModel(
            id = id,
            name = name,
            fileName = fileName,
            sizeBytes = mb * 1024L * 1024L,
            params = params,
            description = description,
            url = url,
            fallbackUrl = fallback,
        )

        private const val HF = "https://hf-mirror.com/bartowski/"
        private const val MS = "https://modelscope.cn/models/Qwen/"

        /** 50 个可直接下载的离线 GGUF 模型，全部经过链接可用性验证。 */
        val REMOTE_MODELS = listOf(
            // ---------- 国产与官方发行 ----------
            m(
                "hunyuan-0.5b", "混元 0.5B（元宝同款）· Q4",
                "hunyuan-0.5b-instruct-q4_k_m.gguf", 339, "0.5B",
                "腾讯混元团队开源的端侧模型，元宝同款，速度极快",
                "https://gh-proxy.com/https://github.com/China-mc-303/LocalAI-Android/releases/download/v3.4.1/hunyuan-0.5b-instruct-q4_k_m.gguf",
                "https://github.com/China-mc-303/LocalAI-Android/releases/download/v3.4.1/hunyuan-0.5b-instruct-q4_k_m.gguf",
            ),
            m(
                "deepseek-r1-1.5b", "DeepSeek-R1 1.5B · Q4",
                "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf", 1065, "1.5B",
                "DeepSeek 官方蒸馏推理模型，与本地AI 助手同源，会先思考再回答",
                "https://modelscope.cn/models/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/master/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
                "https://hf-mirror.com/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            ),
            m(
                "deepseek-r1-7b", "DeepSeek-R1 7B · Q4",
                "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf", 4460, "7B",
                "DeepSeek 官方蒸馏推理模型，数学与逻辑更强，需要 16GB 内存",
                HF + "DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            ),
            m(
                "deepseek-r1-8b-llama", "DeepSeek-R1 8B（Llama 版）· Q4",
                "DeepSeek-R1-Distill-Llama-8B-Q4_K_M.gguf", 4930, "8B",
                "DeepSeek 官方蒸馏推理模型，Llama 底座，英文推理更稳",
                HF + "DeepSeek-R1-Distill-Llama-8B-GGUF/resolve/main/DeepSeek-R1-Distill-Llama-8B-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Llama-8B-GGUF/resolve/main/DeepSeek-R1-Distill-Llama-8B-Q4_K_M.gguf",
            ),
            m(
                "deepseek-coder-v2-lite", "DeepSeek-Coder-V2 Lite · Q4",
                "DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf", 8930, "16B MoE",
                "DeepSeek 官方代码模型，写代码和改 Bug 很强，仅建议 16GB 以上",
                HF + "DeepSeek-Coder-V2-Lite-Instruct-GGUF/resolve/main/DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF/resolve/main/DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf",
            ),
            m(
                "glm-edge-1.5b", "GLM-Edge 1.5B（智谱）· Q4",
                "glm-edge-1.5b-chat-q4_k_m.gguf", 935, "1.5B",
                "智谱清言团队端侧模型，专为手机等边缘设备优化",
                "https://modelscope.cn/models/ZhipuAI/glm-edge-1.5b-chat-gguf/resolve/master/ggml-model-Q4_K_M.gguf",
                "https://hf-mirror.com/zai-org/glm-edge-1.5b-chat-gguf/resolve/main/ggml-model-Q4_K_M.gguf",
            ),
            m(
                "glm4-9b", "GLM-4 9B Chat（智谱）· Q4",
                "glm-4-9b-chat-Q4_K_M.gguf", 5490, "9B",
                "智谱 GLM-4 开源对话模型，中文长文与知识问答表现好",
                HF + "glm-4-9b-chat-GGUF/resolve/main/glm-4-9b-chat-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/glm-4-9b-chat-GGUF/resolve/main/glm-4-9b-chat-Q4_K_M.gguf",
            ),
            m(
                "yi1.5-6b", "Yi-1.5 6B Chat · Q4",
                "Yi-1.5-6B-Chat-Q4_K_M.gguf", 3700, "6B",
                "零一万物 Yi 系列，中英文均衡，适合日常问答",
                HF + "Yi-1.5-6B-Chat-GGUF/resolve/main/Yi-1.5-6B-Chat-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Yi-1.5-6B-Chat-GGUF/resolve/main/Yi-1.5-6B-Chat-Q4_K_M.gguf",
            ),
            m(
                "yi1.5-9b", "Yi-1.5 9B Chat · Q4",
                "Yi-1.5-9B-Chat-Q4_K_M.gguf", 5340, "9B",
                "零一万物 Yi 大杯，理解与写作更强，需要 16GB 内存",
                HF + "Yi-1.5-9B-Chat-GGUF/resolve/main/Yi-1.5-9B-Chat-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Yi-1.5-9B-Chat-GGUF/resolve/main/Yi-1.5-9B-Chat-Q4_K_M.gguf",
            ),
            // ---------- Qwen 系列 ----------
            m(
                "qwen2.5-0.5b", "Qwen2.5 0.5B · Q4",
                "qwen2.5-0.5b-instruct-q4_k_m.gguf", 468, "0.5B",
                "最小最快，适合低配手机，能力有限",
                MS + "Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                "https://hf-mirror.com/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-0.5b-q8", "Qwen2.5 0.5B · Q8 高精度",
                "Qwen2.5-0.5B-Instruct-Q8_0.gguf", 680, "0.5B",
                "0.5B 的高精度版本，回答更稳定，体积略大",
                HF + "Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q8_0.gguf",
                "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q8_0.gguf",
            ),
            m(
                "qwen2.5-1.5b", "Qwen2.5 1.5B · Q4",
                "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1065, "1.5B",
                "推荐：中文能力与速度平衡，8GB 以上可用",
                MS + "Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                "https://hf-mirror.com/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-3b", "Qwen2.5 3B · Q4",
                "qwen2.5-3b-instruct-q4_k_m.gguf", 2007, "3B",
                "效果更好，建议 12GB 内存，速度较慢",
                MS + "Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf",
                "https://hf-mirror.com/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-7b", "Qwen2.5 7B · Q4",
                "Qwen2.5-7B-Instruct-Q4_K_M.gguf", 4468, "7B",
                "旗舰档，效果最好，仅建议 16GB 内存旗舰机",
                HF + "Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-7b-1m", "Qwen2.5 7B 长上下文版 · Q4",
                "Qwen2.5-7B-Instruct-1M-Q4_K_M.gguf", 4680, "7B",
                "支持百万字长上下文的版本，适合读长文档",
                HF + "Qwen2.5-7B-Instruct-1M-GGUF/resolve/main/Qwen2.5-7B-Instruct-1M-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-1M-GGUF/resolve/main/Qwen2.5-7B-Instruct-1M-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-14b", "Qwen2.5 14B · Q4",
                "Qwen2.5-14B-Instruct-Q4_K_M.gguf", 8980, "14B",
                "超大杯，效果接近云端模型，仅限 24GB 内存设备",
                HF + "Qwen2.5-14B-Instruct-GGUF/resolve/main/Qwen2.5-14B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Qwen2.5-14B-Instruct-GGUF/resolve/main/Qwen2.5-14B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-coder-0.5b", "Qwen2.5 Coder 0.5B · Q4",
                "Qwen2.5-Coder-0.5B-Instruct-Q4_K_M.gguf", 400, "0.5B",
                "最小代码模型，补全和简单脚本够用",
                HF + "Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-0.5B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-0.5B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-coder-0.5b-q8", "Qwen2.5 Coder 0.5B · Q8",
                "Qwen2.5-Coder-0.5B-Instruct-Q8_0.gguf", 680, "0.5B",
                "代码小模型高精度版，生成代码更准确",
                HF + "Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-0.5B-Instruct-Q8_0.gguf",
                "https://huggingface.co/bartowski/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-0.5B-Instruct-Q8_0.gguf",
            ),
            m(
                "qwen2.5-coder-1.5b", "Qwen2.5 Coder 1.5B · Q4",
                "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf", 993, "1.5B",
                "代码专精模型，写代码、改 Bug 更在行",
                MS + "Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/master/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
                "https://hf-mirror.com/bartowski/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-1.5B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-coder-3b", "Qwen2.5 Coder 3B · Q4",
                "qwen2.5-coder-3b-instruct-q4_k_m.gguf", 1900, "3B",
                "代码模型进阶款，能写完整模块与小项目",
                MS + "Qwen2.5-Coder-3B-Instruct-GGUF/resolve/master/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
                "https://hf-mirror.com/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-coder-7b", "Qwen2.5 Coder 7B · Q4",
                "qwen2.5-coder-7b-instruct-q4_k_m.gguf", 4460, "7B",
                "代码旗舰，复杂工程与调试，需要 16GB 内存",
                MS + "Qwen2.5-Coder-7B-Instruct-GGUF/resolve/master/qwen2.5-coder-7b-instruct-q4_k_m.gguf",
                "https://hf-mirror.com/bartowski/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-coder-14b", "Qwen2.5 Coder 14B · Q4",
                "Qwen2.5-Coder-14B-Instruct-Q4_K_M.gguf", 8980, "14B",
                "代码超大杯，适合旗舰机与重度开发",
                HF + "Qwen2.5-Coder-14B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-14B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Qwen2.5-Coder-14B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-14B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2.5-math-7b", "Qwen2.5 Math 7B · Q4",
                "Qwen2.5-Math-7B-Instruct-Q4_K_M.gguf", 4430, "7B",
                "数学专精模型，解题与公式推导更强",
                HF + "Qwen2.5-Math-7B-Instruct-GGUF/resolve/main/Qwen2.5-Math-7B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Qwen2.5-Math-7B-Instruct-GGUF/resolve/main/Qwen2.5-Math-7B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen2-7b", "Qwen2 7B · Q4",
                "Qwen2-7B-Instruct-Q4_K_M.gguf", 4430, "7B",
                "上一代 Qwen2 旗舰，稳定可靠",
                HF + "Qwen2-7B-Instruct-GGUF/resolve/main/Qwen2-7B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Qwen2-7B-Instruct-GGUF/resolve/main/Qwen2-7B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "qwen3-0.6b", "Qwen3 0.6B · Q4",
                "Qwen3-0.6B-Q4_K_M.gguf", 428, "0.6B",
                "新一代小模型，支持思考模式开关，速度快",
                "https://hf-mirror.com/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf",
            ),
            m(
                "qwen3-1.7b", "Qwen3 1.7B · Q4",
                "Qwen3-1.7B-Q4_K_M.gguf", 1342, "1.7B",
                "Qwen3 系列均衡款，中文对话质量更好",
                "https://hf-mirror.com/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
                "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            ),
            m(
                "qwen3-4b", "Qwen3 4B · Q4",
                "Qwen3-4B-Q4_K_M.gguf", 2388, "4B",
                "能力明显更强，建议 12GB 以上内存",
                MS + "Qwen3-4B-GGUF/resolve/master/Qwen3-4B-Q4_K_M.gguf",
                "https://hf-mirror.com/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            ),
            m(
                "qwen3-8b", "Qwen3 8B · Q4",
                "Qwen3-8B-Q4_K_M.gguf", 4810, "8B",
                "Qwen3 大杯，综合能力最强，需要 16GB 内存",
                MS + "Qwen3-8B-GGUF/resolve/master/Qwen3-8B-Q4_K_M.gguf",
                "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
            ),
            // ---------- 国际开源系列 ----------
            m(
                "llama3.1-8b", "Llama 3.1 8B Instruct · Q4",
                "Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf", 4920, "8B",
                "Meta 官方开源旗舰，英文与多语言能力强",
                HF + "Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "llama3-8b", "Llama 3 8B Instruct · Q4",
                "Meta-Llama-3-8B-Instruct-Q4_K_M.gguf", 4920, "8B",
                "上一代 Llama 3 旗舰，生态兼容性最好",
                HF + "Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "llama3.2-1b", "Llama 3.2 1B Instruct · Q4",
                "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807, "1B",
                "轻量英文模型，速度极快，适合低配机",
                HF + "Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "llama3.2-1b-q8", "Llama 3.2 1B Instruct · Q8",
                "Llama-3.2-1B-Instruct-Q8_0.gguf", 1310, "1B",
                "1B 高精度版，英文表达更自然",
                HF + "Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q8_0.gguf",
                "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q8_0.gguf",
            ),
            m(
                "llama3.2-3b", "Llama 3.2 3B Instruct · Q4",
                "Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2020, "3B",
                "小而均衡的英文模型，8GB 内存可用",
                HF + "Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "gemma2-2b", "Gemma 2 2B Instruct · Q4",
                "gemma-2-2b-it-Q4_K_M.gguf", 1710, "2B",
                "谷歌 Gemma 2 小杯，指令跟随与安全性好",
                HF + "gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            ),
            m(
                "gemma2-9b", "Gemma 2 9B Instruct · Q4",
                "gemma-2-9b-it-Q4_K_M.gguf", 5480, "9B",
                "谷歌 Gemma 2 大杯，写作与知识问答强，需要 16GB",
                HF + "gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
            ),
            m(
                "phi3.5-mini", "Phi-3.5 Mini Instruct · Q4",
                "Phi-3.5-mini-instruct-Q4_K_M.gguf", 2180, "3.8B",
                "微软 Phi-3.5，小体积推理与代码能力突出",
                HF + "Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            ),
            m(
                "phi3-mini-4k", "Phi-3 Mini 4K Instruct · Q4",
                "Phi-3-mini-4k-instruct-Q4_K_M.gguf", 2320, "3.8B",
                "微软经典小模型，逻辑推理稳定",
                HF + "Phi-3-mini-4k-instruct-GGUF/resolve/main/Phi-3-mini-4k-instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Phi-3-mini-4k-instruct-GGUF/resolve/main/Phi-3-mini-4k-instruct-Q4_K_M.gguf",
            ),
            m(
                "phi3-mini-q8", "Phi-3 Mini 4K · Q8",
                "Phi-3-mini-4k-instruct-Q8_0.gguf", 3800, "3.8B",
                "Phi-3 Mini 高精度版，回答更准确",
                HF + "Phi-3-mini-4k-instruct-GGUF/resolve/main/Phi-3-mini-4k-instruct-Q8_0.gguf",
                "https://huggingface.co/bartowski/Phi-3-mini-4k-instruct-GGUF/resolve/main/Phi-3-mini-4k-instruct-Q8_0.gguf",
            ),
            m(
                "phi3.5-moe", "Phi-3.5 MoE Instruct · Q4",
                "Phi-3.5-MoE-instruct-Q4_K_M.gguf", 8360, "41B MoE",
                "微软混合专家模型，推理强但体积大，需要 24GB 内存",
                HF + "Phi-3.5-MoE-instruct-GGUF/resolve/main/Phi-3.5-MoE-instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Phi-3.5-MoE-instruct-GGUF/resolve/main/Phi-3.5-MoE-instruct-Q4_K_M.gguf",
            ),
            m(
                "mistral-7b-v0.3", "Mistral 7B Instruct v0.3 · Q4",
                "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf", 4370, "7B",
                "Mistral 官方指令模型，英文写作与总结好",
                HF + "Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            ),
            m(
                "mistral-nemo-12b", "Mistral Nemo 12B · Q4",
                "Mistral-Nemo-Instruct-2407-Q4_K_M.gguf", 7100, "12B",
                "Mistral 与英伟达合作款，多语言与长上下文均衡",
                HF + "Mistral-Nemo-Instruct-2407-GGUF/resolve/main/Mistral-Nemo-Instruct-2407-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Mistral-Nemo-Instruct-2407-GGUF/resolve/main/Mistral-Nemo-Instruct-2407-Q4_K_M.gguf",
            ),
            m(
                "mistral-nemo-12b-q8", "Mistral Nemo 12B · Q8",
                "Mistral-Nemo-Instruct-2407-Q8_0.gguf", 13200, "12B",
                "Mistral Nemo 高精度版，效果更好，需要 24GB 内存",
                HF + "Mistral-Nemo-Instruct-2407-GGUF/resolve/main/Mistral-Nemo-Instruct-2407-Q8_0.gguf",
                "https://huggingface.co/bartowski/Mistral-Nemo-Instruct-2407-GGUF/resolve/main/Mistral-Nemo-Instruct-2407-Q8_0.gguf",
            ),
            m(
                "aya-expanse-8b", "Aya Expanse 8B · Q4",
                "aya-expanse-8b-Q4_K_M.gguf", 4880, "8B",
                "Cohere 多语言模型，支持 23 种语言互译",
                HF + "aya-expanse-8b-GGUF/resolve/main/aya-expanse-8b-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/aya-expanse-8b-GGUF/resolve/main/aya-expanse-8b-Q4_K_M.gguf",
            ),
            m(
                "starling-lm-7b", "Starling LM 7B Beta · Q4",
                "Starling-LM-7B-beta-Q4_K_M.gguf", 4370, "7B",
                "伯克利 Starling 奖励模型微调，回答质量稳定",
                HF + "Starling-LM-7B-beta-GGUF/resolve/main/Starling-LM-7B-beta-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/Starling-LM-7B-beta-GGUF/resolve/main/Starling-LM-7B-beta-Q4_K_M.gguf",
            ),
            // ---------- 超小模型与高精度收藏 ----------
            m(
                "smollm2-135m", "SmolLM2 135M · Q4",
                "SmolLM2-135M-Instruct-Q4_K_M.gguf", 110, "135M",
                "超小模型，几乎不占内存，适合体验与测试",
                HF + "SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
            ),
            m(
                "smollm2-360m", "SmolLM2 360M · Q4",
                "SmolLM2-360M-Instruct-Q4_K_M.gguf", 270, "360M",
                "超小模型进阶款，低配手机也能流畅运行",
                HF + "SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf",
            ),
            m(
                "smollm2-360m-q8", "SmolLM2 360M · Q8",
                "SmolLM2-360M-Instruct-Q8_0.gguf", 390, "360M",
                "超小模型高精度版，体积仍然很小",
                HF + "SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q8_0.gguf",
                "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q8_0.gguf",
            ),
            m(
                "smollm2-1.7b", "SmolLM2 1.7B · Q4",
                "SmolLM2-1.7B-Instruct-Q4_K_M.gguf", 1010, "1.7B",
                "轻量通用模型，速度与能力的平衡点",
                HF + "SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
                "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            ),
            m(
                "smollm2-1.7b-q8", "SmolLM2 1.7B · Q8",
                "SmolLM2-1.7B-Instruct-Q8_0.gguf", 1820, "1.7B",
                "轻量模型高精度版，输出更稳定",
                HF + "SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q8_0.gguf",
                "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q8_0.gguf",
            ),
            m(
                "deepseek-r1-1.5b-q40", "DeepSeek-R1 1.5B · Q4_0 省内存",
                "DeepSeek-R1-Distill-Qwen-1.5B-Q4_0.gguf", 1000, "1.5B",
                "DeepSeek 推理模型省内存版本，老手机更友好",
                HF + "DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_0.gguf",
                "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_0.gguf",
            ),
            m(
                "deepseek-coder-v2-lite-q8", "DeepSeek-Coder-V2 Lite · Q8",
                "DeepSeek-Coder-V2-Lite-Instruct-Q8_0.gguf", 16500, "16B MoE",
                "代码模型高精度版，体积很大，仅限 24GB 内存设备",
                HF + "DeepSeek-Coder-V2-Lite-Instruct-GGUF/resolve/main/DeepSeek-Coder-V2-Lite-Instruct-Q8_0.gguf",
                "https://huggingface.co/bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF/resolve/main/DeepSeek-Coder-V2-Lite-Instruct-Q8_0.gguf",
            ),
            m(
                "yi1.5-6b-q8", "Yi-1.5 6B Chat · Q8",
                "Yi-1.5-6B-Chat-Q8_0.gguf", 6500, "6B",
                "Yi 6B 高精度版，中文写作更细腻",
                HF + "Yi-1.5-6B-Chat-GGUF/resolve/main/Yi-1.5-6B-Chat-Q8_0.gguf",
                "https://huggingface.co/bartowski/Yi-1.5-6B-Chat-GGUF/resolve/main/Yi-1.5-6B-Chat-Q8_0.gguf",
            ),
            m(
                "qwen2.5-math-7b-q8", "Qwen2.5 Math 7B · Q8",
                "Qwen2.5-Math-7B-Instruct-Q8_0.gguf", 7800, "7B",
                "数学专精模型高精度版，解题步骤更严谨",
                HF + "Qwen2.5-Math-7B-Instruct-GGUF/resolve/main/Qwen2.5-Math-7B-Instruct-Q8_0.gguf",
                "https://huggingface.co/bartowski/Qwen2.5-Math-7B-Instruct-GGUF/resolve/main/Qwen2.5-Math-7B-Instruct-Q8_0.gguf",
            ),
            m(
                "qwen2.5-14b-q8", "Qwen2.5 14B · Q8 高精度",
                "Qwen2.5-14B-Instruct-Q8_0.gguf", 15600, "14B",
                "14B 高精度版，效果最好，仅限 24GB 内存设备",
                HF + "Qwen2.5-14B-Instruct-GGUF/resolve/main/Qwen2.5-14B-Instruct-Q8_0.gguf",
                "https://huggingface.co/bartowski/Qwen2.5-14B-Instruct-GGUF/resolve/main/Qwen2.5-14B-Instruct-Q8_0.gguf",
            ),
        )
    }
}
