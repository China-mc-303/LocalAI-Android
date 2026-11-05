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

    suspend fun download(model: RemoteModel, onProgress: (Long, Long) -> Unit): LocalModel =
        withContext(Dispatchers.IO) {
            val target = File(modelsDir, model.fileName)
            if (target.exists() && isValidGguf(target)) {
                return@withContext LocalModel(target, model.name, target.length())
            }
            val temp = File(modelsDir, model.fileName + ".part")

            var lastError: Exception? = null
            for (url in listOf(model.url, model.fallbackUrl)) {
                try {
                    downloadFrom(url, temp, onProgress)
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
            if (lastError != null) throw lastError

            verifyGguf(temp)
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IOException("保存模型失败")
            LocalModel(target, model.name, target.length())
        }

    private suspend fun downloadFrom(url: String, temp: File, onProgress: (Long, Long) -> Unit) {
        val existing = if (temp.exists()) temp.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 30_000
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
            val contentLength = connection.contentLengthLong
            val total = if (append) existing + contentLength else contentLength
            var copied = if (append) existing else 0L

            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = copied
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
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
        val REMOTE_MODELS = listOf(
            RemoteModel(
                id = "qwen2.5-0.5b",
                name = "Qwen2.5 0.5B · Q4",
                fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
                sizeBytes = 491_400_032,
                params = "0.5B",
                description = "最小最快，适合低配手机，能力有限",
                url = "https://modelscope.cn/models/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf",
                fallbackUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            ),
            RemoteModel(
                id = "qwen2.5-1.5b",
                name = "Qwen2.5 1.5B · Q4",
                fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
                sizeBytes = 1_117_320_736,
                params = "1.5B",
                description = "推荐：中文能力与速度平衡，8GB 以上可用",
                url = "https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                fallbackUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            ),
            RemoteModel(
                id = "qwen2.5-3b",
                name = "Qwen2.5 3B · Q4",
                fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
                sizeBytes = 2_104_932_768,
                params = "3B",
                description = "效果更好，建议 12GB 内存，速度较慢",
                url = "https://modelscope.cn/models/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf",
                fallbackUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            ),
        )
    }
}
