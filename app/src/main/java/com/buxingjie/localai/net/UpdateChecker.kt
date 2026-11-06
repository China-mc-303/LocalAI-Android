package com.buxingjie.localai.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class GithubAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0,
)

@Serializable
private data class GithubRelease(
    val tag_name: String = "",
    val name: String = "",
    val body: String = "",
    val html_url: String = "",
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

data class UpdateInfo(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
)

object UpdateChecker {

    const val REPO = "China-mc-303/LocalAI-Android"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** GitHub 加速镜像（参考 wenxi 项目），用于 API 与文件下载加速。 */
    private val proxies = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
        "https://github.moeyy.xyz/",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        // 先直连，失败后依次尝试加速镜像
        val urls = listOf(LATEST_URL) + proxies.map { it + LATEST_URL }
        for (url in urls) {
            fetchLatest(url)?.let { return@withContext it }
        }
        null
    }

    private fun fetchLatest(url: String): UpdateInfo? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LocalAI-Android")
        }
        return try {
            if (connection.responseCode != 200) return null
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val release = json.decodeFromString<GithubRelease>(text)
            if (release.prerelease || release.tag_name.isBlank()) return null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: return null
            UpdateInfo(
                version = release.tag_name.trim().removePrefix("v").removePrefix("V"),
                notes = release.body.trim(),
                pageUrl = release.html_url,
                apkUrl = apk.browser_download_url,
                apkSize = apk.size,
            )
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** 比较语义化版本，latest 高于 current 时返回 true。 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int> =
        version.trim()
            .removePrefix("v").removePrefix("V")
            .split(".")
            .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}

object UpdateDownloader {

    private val proxies = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://gh.llkk.cc/",
        "https://github.moeyy.xyz/",
    )

    /** 低于该速度视为线路不佳，自动切换到下一个镜像。 */
    private const val MIN_SPEED_BYTES_PER_SEC = 100 * 1024L
    private const val SPEED_TEST_WINDOW_MS = 8_000L

    suspend fun download(
        url: String,
        target: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val candidates = proxies.map { it + url } + url
        var lastError: Exception? = null
        for (candidate in candidates) {
            try {
                downloadOnce(candidate, target, onProgress)
                return@withContext
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
                runCatching { target.delete() }
            }
        }
        throw lastError ?: IOException("所有下载通道均不可用")
    }

    private fun downloadOnce(
        url: String,
        target: File,
        onProgress: (received: Long, total: Long) -> Unit,
    ) {
        var current = URL(url)
        var redirects = 0
        var connection: HttpURLConnection? = null
        while (true) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LocalAI-Android")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank() || redirects >= 5) {
                    throw IOException("下载地址重定向失败")
                }
                current = URL(current, location)
                redirects++
                continue
            }
            if (code != 200) {
                conn.disconnect()
                throw IOException("服务器返回 HTTP $code")
            }
            connection = conn
            break
        }
        val download = connection ?: throw IOException("下载地址无效")
        try {
            val total = download.contentLengthLong.takeIf { it > 0 } ?: 0L
            var received = 0L
            var lastNotify = 0L
            val startTime = System.currentTimeMillis()
            download.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        received += read
                        if (received - lastNotify >= 256 * 1024) {
                            lastNotify = received
                            onProgress(received, total)
                        }
                        // 速度检测窗口：前 8 秒平均速度过慢则放弃该镜像
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed >= SPEED_TEST_WINDOW_MS &&
                            elapsed < SPEED_TEST_WINDOW_MS + 30_000 &&
                            received < MIN_SPEED_BYTES_PER_SEC * elapsed / 1000
                        ) {
                            throw IOException("当前线路速度过慢，正在切换加速通道…")
                        }
                    }
                    output.flush()
                }
            }
            onProgress(received, total)
            if (total > 0 && received < total) throw IOException("下载不完整，请重试")
        } finally {
            download.disconnect()
        }
    }
}
