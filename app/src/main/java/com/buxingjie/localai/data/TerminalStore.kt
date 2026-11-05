package com.buxingjie.localai.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class TerminalLine(
    val text: String,
    val kind: String,
    val time: Long,
)

/**
 * 每个 AI（本地模型 + 每个在线平台）各自一份终端历史。
 */
class TerminalStore(context: Context) {

    private val dir = File(context.filesDir, "terminal").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(engineId: String): List<TerminalLine> {
        val file = File(dir, "${safeName(engineId)}.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<TerminalLine>>(file.readText())
        }.getOrDefault(emptyList())
    }

    fun save(engineId: String, lines: List<TerminalLine>) {
        runCatching {
            File(dir, "${safeName(engineId)}.json").writeText(
                json.encodeToString(ListSerializer, lines.takeLast(300)),
            )
        }
    }

    fun clear(engineId: String) {
        File(dir, "${safeName(engineId)}.json").delete()
    }

    private fun safeName(engineId: String): String =
        engineId.replace(Regex("[^a-zA-Z0-9_-]"), "_")

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(TerminalLine.serializer())
    }
}
