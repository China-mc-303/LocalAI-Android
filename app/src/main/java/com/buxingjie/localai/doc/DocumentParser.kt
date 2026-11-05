package com.buxingjie.localai.doc

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class DocumentParser(private val context: Context) {

    suspend fun parse(uri: Uri): ParsedDoc = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        parseBytes(name, readBytes(uri))
    }

    /** 解析工作目录等本地路径上的文件。 */
    suspend fun parseFile(file: File): ParsedDoc = withContext(Dispatchers.IO) {
        if (!file.isFile) throw IOException("不是有效文件")
        if (file.length() > MAX_FILE_BYTES) throw IOException("文件超过 80MB，请拆分后再试")
        parseBytes(file.name, file.readBytes())
    }

    /** 列出 zip 压缩包内的文件。 */
    suspend fun listArchive(file: File): List<DocEntry> = withContext(Dispatchers.IO) {
        if (!file.name.lowercase().endsWith(".zip")) throw IOException("暂只支持查看 zip 压缩包")
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .take(300)
                .map { DocEntry(it.name, it.size.toInt()) }
                .toList()
        }
    }

    /** 读取 zip 内某个文件的可读内容。 */
    suspend fun readArchiveEntry(file: File, entryName: String): String = withContext(Dispatchers.IO) {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(entryName) ?: throw IOException("压缩包里没有 $entryName")
            if (entry.size > MAX_FILE_BYTES) throw IOException("该文件超过 80MB")
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val ext = entryName.substringAfterLast('.', "").lowercase()
            when (ext) {
                "pdf" -> parsePdf(entryName, bytes).first
                "docx" -> parseDocx(entryName, bytes).first
                "pptx" -> parsePptx(entryName, bytes).first
                "xlsx" -> parseXlsx(entryName, bytes).first
                "zip" -> parseZip(entryName, bytes, 1).first
                else -> if (looksBinary(bytes)) {
                    throw IOException("这是二进制文件，无法直接预览")
                } else {
                    decodeText(bytes).take(MAX_CHARS_PER_FILE)
                }
            }
        }
    }

    private fun parseBytes(name: String, bytes: ByteArray): ParsedDoc {
        val ext = name.substringAfterLast('.', "").lowercase()

        val (text, entries, note) = when (ext) {
            "pdf" -> parsePdf(name, bytes)
            "docx" -> parseDocx(name, bytes)
            "pptx" -> parsePptx(name, bytes)
            "xlsx" -> parseXlsx(name, bytes)
            "zip" -> parseZip(name, bytes, 0)
            "doc", "xls", "ppt", "rar", "7z" -> Triple("", emptyList(), "暂不支持 .$ext，请转为 docx/pdf/txt 或 zip")
            else -> parsePlain(name, bytes)
        }

        if (text.isBlank()) {
            throw IOException(if (note.isNotBlank()) note else "没有从文件中提取到文字")
        }
        return ParsedDoc(
            id = UUID.randomUUID().toString(),
            name = name,
            text = text,
            entries = entries,
            note = note,
        )
    }

    private fun parsePlain(name: String, bytes: ByteArray): Triple<String, List<DocEntry>, String> {
        if (looksBinary(bytes)) {
            return Triple("", emptyList(), "这是二进制或图片文件，暂不支持读取内容")
        }
        val text = decodeText(bytes).take(MAX_CHARS_PER_FILE)
        val content = "【文件：$name】\n$text"
        return Triple(content, listOf(DocEntry(name, text.length)), "")
    }

    private fun parsePdf(name: String, bytes: ByteArray): Triple<String, List<DocEntry>, String> {
        return try {
            PDDocument.load(ByteArrayInputStream(bytes)).use { document ->
                val text = PDFTextStripper().getText(document).take(MAX_CHARS_PER_FILE)
                Triple("【文件：$name】\n$text", listOf(DocEntry(name, text.length)), "")
            }
        } catch (e: Exception) {
            Triple("", emptyList(), "PDF 解析失败：${e.message}")
        }
    }

    private fun parseDocx(name: String, bytes: ByteArray): Triple<String, List<DocEntry>, String> {
        val xml = readZipEntry(bytes, "word/document.xml") ?: return Triple("", emptyList(), "无法读取 docx 内容")
        val text = xmlToText(xml, "</w:p>").take(MAX_CHARS_PER_FILE)
        return Triple("【文件：$name】\n$text", listOf(DocEntry(name, text.length)), "")
    }

    private fun parsePptx(name: String, bytes: ByteArray): Triple<String, List<DocEntry>, String> {
        val entries = ArrayList<DocEntry>()
        val builder = StringBuilder()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (Regex("ppt/slides/slide\\d+\\.xml").matches(entry.name)) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    val text = xmlToText(xml, "</a:p>")
                    if (text.isNotBlank()) {
                        builder.append("【").append(entry.name.substringAfterLast('/')).append("】\n")
                        builder.append(text).append("\n\n")
                        entries.add(DocEntry(entry.name, text.length))
                    }
                }
                zip.closeEntry()
            }
        }
        return Triple("【文件：$name】\n$builder", entries, "")
    }

    private fun parseXlsx(name: String, bytes: ByteArray): Triple<String, List<DocEntry>, String> {
        val shared = readZipEntry(bytes, "xl/sharedStrings.xml")?.let { xmlToText(it) } ?: ""
        val builder = StringBuilder(shared).append('\n')
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (Regex("xl/worksheets/sheet\\d+\\.xml").matches(entry.name)) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    val cells = Regex("<v>(.*?)</v>").findAll(xml).joinToString(" ") { it.groupValues[1] }
                    if (cells.isNotBlank()) builder.append(cells).append('\n')
                }
                zip.closeEntry()
            }
        }
        val text = builder.toString().take(MAX_CHARS_PER_FILE)
        return Triple("【文件：$name】\n$text", listOf(DocEntry(name, text.length)), "")
    }

    private fun parseZip(name: String, bytes: ByteArray, depth: Int): Triple<String, List<DocEntry>, String> {
        val builder = StringBuilder()
        val entries = ArrayList<DocEntry>()
        var totalChars = 0
        var count = 0

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                count++
                if (count > MAX_ZIP_ENTRIES || totalChars > MAX_ZIP_CHARS) {
                    zip.closeEntry()
                    continue
                }
                val entryName = entry.name
                val entryBytes = runCatching { zip.readBytes() }.getOrElse { ByteArray(0) }
                val ext = entryName.substringAfterLast('.', "").lowercase()

                val extracted = when {
                    ext == "zip" && depth < 2 -> {
                        val nested = parseZip(entryName, entryBytes, depth + 1)
                        nested.second.forEach { entries.add(it) }
                        nested.first
                    }
                    ext == "pdf" -> entryBytesToPdf(entryName, entryBytes)
                    ext == "docx" -> entryBytesToDocx(entryName, entryBytes)
                    ext == "pptx" -> entryBytesToPptx(entryName, entryBytes)
                    ext == "xlsx" -> entryBytesToXlsx(entryName, entryBytes)
                    looksBinary(entryBytes) -> ""
                    else -> "【文件：$entryName】\n" + decodeText(entryBytes).take(MAX_CHARS_PER_FILE / 2)
                }
                if (extracted.isNotBlank()) {
                    builder.append(extracted).append("\n\n")
                    totalChars += extracted.length
                    if (entries.none { it.path == entryName }) {
                        entries.add(DocEntry(entryName, extracted.length))
                    }
                }
                zip.closeEntry()
            }
        }
        return Triple("【压缩包：$name】\n$builder", entries, "")
    }

    private fun entryBytesToPdf(name: String, bytes: ByteArray): String {
        val result = parsePdf(name, bytes)
        return if (result.first.isBlank()) "" else result.first
    }

    private fun entryBytesToDocx(name: String, bytes: ByteArray): String = parseDocx(name, bytes).first

    private fun entryBytesToPptx(name: String, bytes: ByteArray): String = parsePptx(name, bytes).first

    private fun entryBytesToXlsx(name: String, bytes: ByteArray): String = parseXlsx(name, bytes).first

    private fun readZipEntry(bytes: ByteArray, path: String): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == path) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
        }
        return null
    }

    private fun xmlToText(xml: String, paragraphBreak: String = ""): String {
        var text = xml
            .replace("</script>", "")
            .replace(Regex("</style>"), "")
            .replace("</w:p>", "\n")
            .replace(paragraphBreak, "\n")
            .replace(Regex("<script\\b[^>]*>[\\s\\S]*?</script>"), "")
            .replace(Regex("<style\\b[^>]*>[\\s\\S]*?</style>"), "")
            .replace(Regex("<[^>]+>"), "")
        text = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&nbsp;", " ")
            .replace("&#39;", "'")
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun readBytes(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开文件")
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                total += read
                if (total > MAX_FILE_BYTES) throw IOException("文件超过 80MB，请拆分后再试")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, charset("GB18030"))
        }
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(4096)
        val nulls = sample.count { it == 0.toByte() }
        return nulls > 8
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "文件"
    }

    private companion object {
        const val MAX_FILE_BYTES = 80L * 1024 * 1024
        const val MAX_CHARS_PER_FILE = 150_000
        const val MAX_ZIP_ENTRIES = 120
        const val MAX_ZIP_CHARS = 300_000
    }
}
