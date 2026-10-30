package com.radium.inkwell.core.parser.txt

/**
 * txt 拆章：多套内置规则逐一评估，命中最多且分布合理者胜出；
 * 全部失败则按固定字数强切。产出的是字符区间（相对全文解码后的字符串）。
 */
object TxtChapterSplitter {

    data class Split(
        val title: String,
        /** 章节内容（含标题行）的字符区间，end 开区间 */
        val start: Int,
        val end: Int,
        val level: Int = 1,
    )

    data class Config(
        val customPattern: Regex? = null,
        val maxTitleLength: Int = 40,
        val fallbackSliceChars: Int = 10_000,
        val minMatches: Int = 3,
    )

    val VOLUME = Regex("""^\s*第\s*[0-9零〇一二三四五六七八九十百千两]+\s*[卷部集]\s*\S{0,30}\s*$""")
    val CHAPTER = Regex("""^\s*第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*[章节回篇话]\s*.{0,30}$""")
    val SPECIAL = Regex("""^\s*(序章?|楔子|引子|番外\S{0,10}|尾声|后记|终章|終章)\s*\S{0,20}\s*$""")
    val EN = Regex("""^\s*Chapter\s+([0-9]+|[IVXLCivxlc]+)\b.{0,40}$""")
    val NUMERIC = Regex("""^\s*[(（]?[0-9零一二三四五六七八九十百]{1,6}[)）、.．]\s*\S{0,25}\s*$""")

    private val candidateRules = listOf(
        listOf(CHAPTER, VOLUME, SPECIAL),   // 主规则：章 + 卷 + 特殊章
        listOf(EN),
        listOf(NUMERIC),
    )

    fun split(text: String, config: Config = Config()): List<Split> {
        // 超过标题长度上限的行永远不可能是标题，扫描时就不收
        val lines = scanLines(text, config.maxTitleLength)

        config.customPattern?.let { custom ->
            val hits = lines.filter { custom.matches(it.content) }
            if (hits.isNotEmpty()) return buildSplits(text, hits, emptySet())
        }

        for (rules in candidateRules) {
            val main = rules.first()
            val hits = lines.filter { line -> rules.any { it.matches(line.content) } }
            val mainHits = hits.count { main.matches(it.content) }
            if (mainHits >= config.minMatches && distributionSane(hits, text.length)) {
                val volumeLines = if (VOLUME in rules) {
                    hits.filter { VOLUME.matches(it.content) }.map { it.start }.toSet()
                } else emptySet()
                return buildSplits(text, hits, volumeLines)
            }
        }

        return fallbackSplit(text, config.fallbackSliceChars)
    }

    /**
     * 一行的位置：[start] 是整行起点（章节区间从这里切），[content] 是去掉首尾空白后的视图。
     *
     * 内容是**视图不是拷贝**：从前每个非空行都 `substring` 一份再 `trim` 一份、整张表撑到拆章结束，
     * 等于把全文又复制了一遍（大 txt 的峰值内存就这么翻上去的）。现在只记偏移，标题文本到建章时才取出来。
     */
    private class Line(private val text: String, val start: Int, private val from: Int, private val to: Int) {
        val content: CharSequence get() = java.nio.CharBuffer.wrap(text, from, to)
        fun title(): String = text.substring(from, to)
    }

    /**
     * 逐行扫描，只收 trim 后非空且不长于 [maxLength] 的行。trim 判据与 `String.trim()` 一致（[Char.isWhitespace]），
     * 行尾的 `\r` 也在其中 —— 拆章结果与从前逐行 `substring().trim()` 完全相同。
     */
    private fun scanLines(text: String, maxLength: Int): List<Line> {
        val lines = mutableListOf<Line>()
        var start = 0
        while (start < text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            var from = start
            var to = end
            while (from < to && text[from].isWhitespace()) from++
            while (to > from && text[to - 1].isWhitespace()) to--
            if (to > from && to - from <= maxLength) lines += Line(text, start, from, to)
            start = end + 1
        }
        return lines
    }

    /** 命中标题分布检查：平均章长不小于 200 字，防止把对话列表当章节 */
    private fun distributionSane(hits: List<Line>, totalLength: Int): Boolean {
        if (hits.isEmpty()) return false
        return totalLength / hits.size >= 200
    }

    private fun buildSplits(text: String, hits: List<Line>, volumeStarts: Set<Int>): List<Split> {
        val splits = mutableListOf<Split>()
        // 第一个标题前的内容作为"前言"章（判空白不必先把这一大段拷出来）
        val firstStart = hits.first().start
        if (firstStart > 0 && (0 until firstStart).any { !text[it].isWhitespace() }) {
            splits += Split("前言", 0, firstStart)
        }
        hits.forEachIndexed { i, line ->
            val end = if (i + 1 < hits.size) hits[i + 1].start else text.length
            splits += Split(
                title = line.title(),
                start = line.start,
                end = end,
                level = if (line.start in volumeStarts) 0 else 1,
            )
        }
        return splits
    }

    private fun fallbackSplit(text: String, sliceChars: Int): List<Split> {
        if (text.isBlank()) return emptyList()
        val splits = mutableListOf<Split>()
        var start = 0
        var index = 1
        while (start < text.length) {
            var end = (start + sliceChars).coerceAtMost(text.length)
            if (end < text.length) {
                // 在目标点之后找最近的换行，别把段落切碎
                val nl = text.indexOf('\n', end)
                end = if (nl in end until end + 2000) nl + 1 else end
            }
            splits += Split("第 $index 部分", start, end)
            start = end
            index++
        }
        return splits
    }
}
