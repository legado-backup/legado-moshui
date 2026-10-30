package com.radium.inkwell.core.parser.mobi

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** INDX 解析对损坏输入的防御：手工拼 header 记录 + CNCX 记录 */
class IndxParserTest {

    /** 最小 INDX header：0 条数据记录、1 条 CNCX 记录，TAGX 只有一个 EOF 标记 */
    private fun header(): ByteArray {
        val b = ByteArray(0xC0 + 16)
        fun put(pos: Int, s: String) = s.forEachIndexed { i, c -> b[pos + i] = c.code.toByte() }
        fun u32(pos: Int, v: Int) {
            b[pos] = (v ushr 24).toByte(); b[pos + 1] = (v ushr 16).toByte()
            b[pos + 2] = (v ushr 8).toByte(); b[pos + 3] = v.toByte()
        }
        put(0, "INDX")
        u32(0x18, 0)        // 数据记录数
        u32(0x34, 1)        // CNCX 记录数
        u32(0xB4, 0xC0)     // TAGX 偏移
        put(0xC0, "TAGX")
        u32(0xC4, 16)       // TAGX 长度（12 字节头 + 1 条 4 字节标签）
        u32(0xC8, 1)        // control byte 数
        b[0xCC + 3] = 1     // EOF 标签
        return b
    }

    /**
     * `0F 7F 7F 7F FB` 这个 varint 截成 Int 是 -5：从前 `pos += consumed + len.toInt()` 原地不动，
     * 打开这本书就是一个停不下来的死循环。超时设在这里，保护失效时的表现是挂住而不是断言失败。
     */
    @Test(timeout = 5_000)
    fun `损坏的 CNCX 长度不会让解析原地打转`() {
        val cncx = byteArrayOf(0x83.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte()) +
            byteArrayOf(0x0F, 0x7F, 0x7F, 0x7F, 0xFB.toByte(), 0x81.toByte(), 'x'.code.toByte())
        val records = listOf(header(), cncx)
        val index = assertNotNull(IndxParser.read(0, { records.getOrNull(it) }, Charsets.UTF_8))
        // 损坏之前的字符串照常可用，损坏处之后放弃
        assertEquals(mapOf(0L to "abc"), index.cncx)
    }

    @Test(timeout = 5_000)
    fun `长度超出记录剩余字节就停`() {
        val cncx = byteArrayOf(0x85.toByte(), 'a'.code.toByte(), 'b'.code.toByte())
        val records = listOf(header(), cncx)
        val index = assertNotNull(IndxParser.read(0, { records.getOrNull(it) }, Charsets.UTF_8))
        assertEquals(emptyMap(), index.cncx)
    }
}
