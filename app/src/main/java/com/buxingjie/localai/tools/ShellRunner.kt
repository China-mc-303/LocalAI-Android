package com.buxingjie.localai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.IOException

data class WorkspaceFile(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
)

data class ShellResult(
    val ok: Boolean,
    val output: String,
    val exitCode: Int = if (ok) 0 else 1,
)

/**
 * 电脑 CMD 风格的常驻终端：同一个 sh 进程持续使用，
 * 所以 cd 会保持目录、环境变量也会保留。授权 Shizuku 后自动切换成 shell 身份。
 */
object ShellRunner {

    private const val MAX_OUTPUT = 8000
    private const val TIMEOUT_MS = 20_000L

    private val mutex = Mutex()
    private var session: PersistentShell? = null
    private var sessionDir: String = ""
    private var sessionShizuku: Boolean = false

    @Volatile
    private var cwd: String = ""

    /** 终端提示符显示的当前目录。 */
    fun currentDir(workDir: String): String {
        val current = cwd
        return if (current.isNotBlank() && File(current).isDirectory) current else workDir
    }

    /** 关闭常驻终端，下次命令会重新启动。 */
    fun reset() {
        runCatching { session?.close() }
        session = null
        cwd = ""
    }

    suspend fun run(command: String, workDir: String): ShellResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val dir = File(workDir).takeIf { it.isDirectory } ?: File("/")
            val shizuku = ShizukuShell.granted()
            if (session == null || sessionDir != dir.absolutePath || sessionShizuku != shizuku) {
                reset()
                session = runCatching { PersistentShell(dir, shizuku) }.getOrNull()
                sessionDir = dir.absolutePath
                sessionShizuku = shizuku
                if (session != null) cwd = dir.absolutePath
            }
            val shell = session ?: return@withContext ShellResult(
                false,
                "无法启动终端。\n工作目录：${dir.absolutePath}",
            )

            val result = shell.run(command, TIMEOUT_MS)
            if (result.ok || result.exitCode >= 0) {
                val newDir = shell.cwd
                if (newDir.isNotBlank()) cwd = newDir
            }
            if (shell.dead) {
                runCatching { shell.close() }
                session = null
            }
            result
        }
    }

    fun listDir(path: String): List<WorkspaceFile> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.take(300)
            ?.map { file ->
                WorkspaceFile(
                    name = file.name,
                    path = file.absolutePath,
                    isDir = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0L,
                )
            }
            ?: emptyList()
    }

    fun ensureDir(path: String): Boolean {
        val dir = File(path)
        return dir.exists() || dir.mkdirs()
    }
}

private class PersistentShell(dir: File, useShizuku: Boolean) {

    private val process: Process
    private val writer: BufferedWriter
    private val lock = Object()
    private val buffer = StringBuilder()
    private val nonce = System.nanoTime().toString()

    @Volatile
    var cwd: String = dir.absolutePath
        private set

    @Volatile
    var dead: Boolean = false
        private set

    private var pendingMarker: String? = null
    private var sawMarker = false
    private var exitCode = 0

    init {
        // Shizuku 失败时自动退回普通应用进程，保证终端一定可用
        val shizukuProcess = if (useShizuku) ShizukuShell.newShell(dir) else null
        process = shizukuProcess ?: ProcessBuilder("/system/bin/sh")
            .apply {
                directory(dir)
                redirectErrorStream(true)
                environment()["LANG"] = "en_US.UTF-8"
                environment()["LC_ALL"] = "en_US.UTF-8"
                environment()["TMPDIR"] = "/data/local/tmp"
            }
            .start()
        writer = process.outputStream.bufferedWriter(Charsets.UTF_8)

        Thread {
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(lock) {
                            val marker = pendingMarker
                            if (marker != null && line.startsWith(marker)) {
                                val parts = line.split('\t')
                                exitCode = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                                parts.getOrNull(2)?.trim()?.takeIf { it.isNotBlank() }?.let { cwd = it }
                                sawMarker = true
                                lock.notifyAll()
                            } else {
                                if (buffer.length < MAX) {
                                    buffer.append(line).append('\n')
                                }
                            }
                        }
                    }
                }
            }
            synchronized(lock) {
                dead = true
                sawMarker = true
                lock.notifyAll()
            }
        }.apply { isDaemon = true }.start()
    }

    fun run(command: String, timeoutMs: Long): ShellResult {
        // 空命令直接返回当前目录
        if (command.isBlank()) return ShellResult(true, "")

        synchronized(lock) {
            if (dead) return ShellResult(false, "终端已退出，请重新打开终端", -1)
            buffer.setLength(0)
            val marker = "__LOCALAI_DONE_${nonce}_${System.nanoTime()}"
            pendingMarker = marker
            sawMarker = false
            exitCode = 0
            try {
                // 每条命令单独执行，记录退出码和当前目录，便于 cd 之后提示符跟着变
                writer.write(command)
                writer.write("\n")
                writer.write("printf '%s\\t%s\\t%s\\n' \"$marker\" \"\$?\" \"\$PWD\"\n")
                writer.flush()
            } catch (e: Exception) {
                dead = true
                return ShellResult(false, "终端写入失败：${e.message}", -1)
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (!sawMarker) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                try {
                    lock.wait(minOf(left, 200))
                } catch (_: InterruptedException) {
                    break
                }
            }

            val timedOut = !sawMarker
            pendingMarker = null
            if (timedOut) {
                dead = true
                runCatching { process.destroyForcibly() }
                val partial = synchronized(lock) { buffer.toString().trim() }
                return ShellResult(
                    false,
                    buildString {
                        appendLine("命令执行超过 ${timeoutMs / 1000} 秒，已强制终止。")
                        appendLine("提示：交互式命令（如 top、logcat）请加超时参数，例如 timeout 5 top")
                        if (partial.isNotBlank()) appendLine().append(partial)
                    },
                    -1,
                )
            }

            val text = synchronized(lock) { buffer.toString() }.trimEnd()
            val ok = exitCode == 0
            val shown = if (text.length > MAX) {
                text.take(MAX) + "\n…（输出过长，已截断）"
            } else {
                text.ifBlank { if (ok) "（命令没有输出）" else "（命令失败，退出码 $exitCode）" }
            }
            return ShellResult(ok, shown, exitCode)
        }
    }

    fun close() {
        synchronized(lock) {
            dead = true
            sawMarker = true
            lock.notifyAll()
        }
        runCatching { writer.close() }
        runCatching { process.destroyForcibly() }
    }

    companion object {
        const val MAX = 8000
    }
}
