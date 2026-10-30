package com.radium.inkwell.core.parser.txt

import com.radium.inkwell.core.model.BookHandle
import com.radium.inkwell.core.model.BookMetadata
import com.radium.inkwell.core.model.BookParseException
import com.radium.inkwell.core.model.BookParser
import com.radium.inkwell.core.model.ChapterContent
import com.radium.inkwell.core.model.ChapterRef
import com.radium.inkwell.core.model.ContentElement
import com.radium.inkwell.core.model.ImageBlob
import com.radium.inkwell.core.model.TocEntry
import java.io.File
import java.io.InputStreamReader

class TxtParser(
    private val splitConfig: TxtChapterSplitter.Config = TxtChapterSplitter.Config(),
) : BookParser {

    override fun sniff(file: File): Boolean {
        if (!file.extension.equals("txt", ignoreCase = true)) return false
        return file.isFile && file.length() > 0
    }

    override fun open(file: File): BookHandle {
        val text = try {
            readText(file)
        } catch (e: Exception) {
            throw BookParseException.Corrupted("读取文件失败: ${file.name}", e)
        }
        val splits = TxtChapterSplitter.split(text, splitConfig)
        if (splits.isEmpty()) throw BookParseException.Corrupted("空文件: ${file.name}")
        return TxtBookHandle(file.nameWithoutExtension, text, splits)
    }

    /**
     * 流式解码整本 txt。
     *
     * 从前 `readBytes()` + `String(bytes)`：解码那一刻原始字节、解码器的字符缓冲、结果字符串三份同时在堆上，
     * 原始字节还一直被 open() 的局部变量攥着撑到拆章结束 —— 几十 MB 的 txt 峰值是文件的三四倍，低端机直接 OOM。
     * 编码只看头部 64KB 就定得下来；正文从 Reader 一路读进按头部字符/字节比例预估好容量的 StringBuilder，
     * 堆上不再有整份原始字节，也省掉 StringBuilder 反复翻倍扩容的拷贝。解码语义不变（非法字节同样替换成 U+FFFD）。
     */
    private fun readText(file: File): String {
        val head = file.inputStream().use { input ->
            val buf = ByteArray(minOf(file.length(), HEAD_BYTES.toLong()).toInt())
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n == buf.size) buf else buf.copyOf(n)
        }
        val charset = EncodingDetector.detect(head)
        val bom = EncodingDetector.bomLength(head)
        val sampleBytes = head.size - bom
        val ratio = if (sampleBytes > 0) String(head, bom, sampleBytes, charset).length.toDouble() / sampleBytes else 1.0
        // 字符数不会多于字节数（任何编码都至少一字节一字符），以文件长度封顶；估不准也只是容量提示
        val capacity = ((file.length() - bom) * minOf(ratio * 1.02, 1.0)).toLong().coerceIn(16, MAX_CAPACITY)
        val sb = StringBuilder(capacity.toInt())
        file.inputStream().buffered().use { input ->
            var skipped = 0L
            while (skipped < bom) {
                val s = input.skip(bom - skipped)
                if (s <= 0) break
                skipped += s
            }
            InputStreamReader(input, charset).use { reader ->
                val buf = CharArray(64 * 1024)
                while (true) {
                    val r = reader.read(buf)
                    if (r < 0) break
                    sb.append(buf, 0, r)
                }
            }
        }
        return sb.toString()
    }

    private companion object {
        /** 编码探测的样本长度 */
        const val HEAD_BYTES = 64 * 1024
        const val MAX_CAPACITY = Int.MAX_VALUE - 16L
    }
}

private class TxtBookHandle(
    title: String,
    private val text: String,
    private val splits: List<TxtChapterSplitter.Split>,
) : BookHandle {

    override val metadata = BookMetadata(title = title)

    override val chapters: List<ChapterRef> =
        splits.mapIndexed { i, s -> ChapterRef(i, s.title) }

    override val toc: List<TocEntry> =
        splits.mapIndexed { i, s -> TocEntry(s.title, i, level = s.level) }

    override fun loadChapter(index: Int): ChapterContent {
        val split = splits[index]
        val body = text.substring(split.start, split.end)
        val paragraphs = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterIndexed { i, line -> !(i == 0 && line == split.title) } // 标题行由排版层渲染
            .map { ContentElement.Paragraph(it) as ContentElement }
            .toList()
        return ChapterContent(paragraphs)
    }

    override fun loadResource(resourceId: String): ImageBlob? = null

    override fun close() {}
}
