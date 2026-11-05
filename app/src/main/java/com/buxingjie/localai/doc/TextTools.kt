package com.buxingjie.localai.doc

data class DocEntry(
    val path: String,
    val chars: Int,
    val note: String = "",
)

data class ParsedDoc(
    val id: String,
    val name: String,
    val text: String,
    val entries: List<DocEntry>,
    val note: String = "",
) {
    val charCount: Int get() = text.length
}

object TextTools {

    fun chunk(text: String, size: Int = 700, overlap: Int = 120): List<String> {
        if (text.isBlank()) return emptyList()
        val chunks = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + size).coerceAtMost(text.length)
            var cut = end
            if (end < text.length) {
                val window = text.substring(start, end)
                val boundary = window.lastIndexOfAny(charArrayOf('\n', '。', '！', '？', '.', '!', '?'))
                if (boundary > size / 2) cut = start + boundary + 1
            }
            val piece = text.substring(start, cut).trim()
            if (piece.length >= 20) chunks.add(piece)
            if (cut >= text.length) break
            start = (cut - overlap).coerceAtLeast(start + 1)
        }
        return chunks
    }

    fun queryTerms(query: String): List<String> {
        val terms = LinkedHashSet<String>()
        Regex("[A-Za-z0-9_\\.]{2,}").findAll(query).forEach { terms.add(it.value.lowercase()) }
        val cjk = query.filter { it.code in 0x4E00..0x9FFF }
        if (cjk.length == 1) {
            terms.add(cjk)
        } else {
            for (i in 0 until cjk.length - 1) {
                terms.add(cjk.substring(i, i + 2))
            }
        }
        return terms.toList()
    }

    fun retrieve(query: String, context: String, maxChars: Int): String {
        val chunks = chunk(context)
        if (chunks.isEmpty()) return ""
        val terms = queryTerms(query)
        if (terms.isEmpty()) return takeEvenly(context, maxChars)

        val avgLength = chunks.sumOf { it.length }.toDouble() / chunks.size
        val docFreq = HashMap<String, Int>()
        for (term in terms) {
            docFreq[term] = chunks.count { it.contains(term, ignoreCase = true) }
        }
        val k1 = 1.2
        val b = 0.75
        val scored = chunks.map { chunk ->
            var score = 0.0
            for (term in terms) {
                val tf = countOccurrences(chunk, term)
                if (tf == 0) continue
                val df = docFreq[term] ?: 0
                val idf = Math.log(1.0 + chunks.size.toDouble() / (df + 0.5))
                score += idf * (tf * (k1 + 1.0)) /
                    (tf + k1 * (1.0 - b + b * chunk.length / avgLength))
            }
            chunk to score
        }.sortedByDescending { it.second }

        val selected = ArrayList<String>()
        var used = 0
        for ((chunk, _) in scored) {
            if (used + chunk.length > maxChars) continue
            selected.add(chunk)
            used += chunk.length
            if (used >= maxChars) break
        }
        if (selected.isEmpty()) return takeEvenly(context, maxChars)
        return selected.joinToString("\n\n")
    }

    fun takeEvenly(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val count = 4
        val slice = maxChars / count
        val step = (text.length - slice) / (count - 1)
        return (0 until count).joinToString("\n\n") { index ->
            val start = (index * step).coerceAtMost(text.length - slice)
            text.substring(start, start + slice)
        }
    }

    private fun countOccurrences(text: String, term: String): Int {
        var count = 0
        var index = text.indexOf(term, ignoreCase = true)
        while (index >= 0) {
            count++
            index = text.indexOf(term, index + term.length, ignoreCase = true)
        }
        return count
    }
}
