package com.buxingjie.localai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class WorkspaceFile(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
)

data class ShellResult(
    val ok: Boolean,
    val output: String,
)

/**
 * 在用户设置的工作目录里执行 shell 命令，供终端和 AI 使用。
 */
object ShellRunner {

    private const val MAX_OUTPUT = 4000
    private const val TIMEOUT_SECONDS = 15L

    suspend fun run(command: String, workDir: String): ShellResult = withContext(Dispatchers.IO) {
        val dir = File(workDir)
        val target = if (dir.isDirectory) dir else File("/")
        val process = ProcessBuilder("/system/bin/sh", "-c", command)
            .apply {
                directory(target)
                redirectErrorStream(true)
                // 让 ls/cat 等命令正确处理中文文件名
                environment()["LANG"] = "en_US.UTF-8"
                environment()["LC_ALL"] = "en_US.UTF-8"
                environment()["TMPDIR"] = System.getProperty("java.io.tmpdir") ?: "/data/local/tmp"
            }
            .start()

        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (output.length < MAX_OUTPUT) output.appendLine(line)
                }
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            ShellResult(false, buildTimeout(output, workDir))
        } else {
            reader.join(1500)
            val text = output.toString().trim().ifBlank { "（命令没有输出）" }
            ShellResult(process.exitValue() == 0, text.take(MAX_OUTPUT) + truncatedHint(output))
        }
    }

    private fun buildTimeout(output: StringBuilder, workDir: String): String =
        "命令执行超过 ${TIMEOUT_SECONDS} 秒，已终止。\n工作目录：$workDir" +
            if (output.isNotBlank()) "\n\n已产生输出：\n${output.toString().take(MAX_OUTPUT)}" else ""

    private fun truncatedHint(output: StringBuilder): String =
        if (output.length >= MAX_OUTPUT) "\n…（输出过长，已截断）" else ""

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
