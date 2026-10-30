package com.radium.inkwell.reader.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 每一款纸张主题的正文对比度都必须 ≥ 7:1（WCAG AAA）。
 *
 * 不是 4.5:1（AA）就算过 —— AA 那条线是给「看一眼就走」的界面定的（按钮、标签）。
 * 阅读器上一坐就是一小时，配色差一点，代价是眼睛疼，而这种代价用户说不清来源，
 * 只会觉得「这 App 看久了累」。
 *
 * 这个测试的价值在**将来**：以后谁再加一款好看的主题，配色不合格会直接挂在这里，
 * 而不是靠人肉审色。
 */
class ReaderThemeContrastTest {

    private fun contrast(t: ReaderTheme) =
        ReaderTheme.contrastRatio(t.background, t.textColor)

    @Test
    fun `所有主题的正文对比度达到 AAA`() {
        val failures = ReaderTheme.ALL.filter { contrast(it) < 7.0 }
            .map { "${it.label}(${it.id}) = %.2f:1".format(contrast(it)) }
        assertTrue(
            failures.isEmpty(),
            "这些主题的正文对比度不到 7:1（AAA），读久了眼睛疼：\n  " + failures.joinToString("\n  "),
        )
    }

    /** 标题比正文更重要，不该比正文还难认 */
    @Test
    fun `标题对比度不低于正文`() {
        ReaderTheme.ALL.forEach { t ->
            val title = ReaderTheme.contrastRatio(t.background, t.titleColor)
            assertTrue(
                title >= contrast(t) - 0.01,
                "${t.label} 的标题(%.2f:1)比正文(%.2f:1)还淡".format(title, contrast(t)),
            )
        }
    }

    /**
     * 页眉页脚是辅助信息（页码、章节名），本就该弱于正文 —— 但也不能弱到看不清。
     * 它是 11~12sp 的小字，纸面上的提示（「已经是最后一页了」、换源进度、返回书架）也用它，
     * 所以按小字的 4.5:1（AA）算，不是大字/非正文的 3:1。从前暖纸、夜色一档只有 3.4:1 上下。
     */
    @Test
    fun `页眉页脚弱于正文但够小字的 AA`() {
        ReaderTheme.ALL.forEach { t ->
            val footer = ReaderTheme.contrastRatio(t.background, t.footerColor)
            assertTrue(
                footer >= ReaderTheme.MIN_SMALL_TEXT_CONTRAST,
                "${t.label} 的页脚只有 %.2f:1，小字看不清".format(footer),
            )
            assertTrue(footer <= contrast(t), "${t.label} 的页脚比正文还抢眼")
        }
    }

    /**
     * 浮层里的次要文字（ReaderThemeScope 的 onSurfaceVariant）：从前「正文色 × 0.7 透明度」一刀切，
     * 夜色/墨蓝/纯黑、宣纸/牛皮上只剩 3.9~4.2:1。钉死 ≥ 4.5:1，且仍弱于正文（不然分不出主次）。
     */
    @Test
    fun `面板次要文字够 AA 且弱于正文`() {
        ReaderTheme.ALL.forEach { t ->
            val secondary = ReaderTheme.contrastRatio(t.background, t.secondaryTextColor)
            assertTrue(
                secondary >= ReaderTheme.MIN_SMALL_TEXT_CONTRAST,
                "${t.label} 的次要文字只有 %.2f:1".format(secondary),
            )
            assertTrue(secondary < contrast(t), "${t.label} 的次要文字和正文一样重，分不出主次")
        }
    }

    /** 本来就够线的主题，次要文字维持「正文退三成」的老样子 —— 修对比度不该顺手把它们也改了 */
    @Test
    fun `够线的主题次要文字不变`() {
        val t = ReaderTheme.PAPER
        // 0.3 = 从前 alpha 0.7 叠在纸上的混合比例；暖纸本来就有 4.65:1
        assertEquals(0xFF726A5CL, t.secondaryTextColor)
    }

    @Test
    fun `自定义主题的页脚也守住小字下限`() {
        // 中等对比的自定义配色：照旧往纸色退 45% 会掉到 4.5 以下
        val t = ReaderTheme.custom(0xFFF2E8D5, 0xFF4A3F30)
        val footer = ReaderTheme.contrastRatio(t.background, t.footerColor)
        assertTrue(footer >= ReaderTheme.MIN_SMALL_TEXT_CONTRAST, "自定义页脚只有 %.2f:1".format(footer))
        assertTrue(footer < ReaderTheme.contrastRatio(t.background, t.textColor))
    }

    @Test
    fun `字色本身不够线时页脚只能退回字色`() {
        // 用户自定义了一套低对比配色：比字色更清楚的颜色就不是这套配色了，别凭空造
        val t = ReaderTheme.custom(0xFF777777, 0xFF555555)
        assertEquals(t.textColor, t.footerColor)
    }

    @Test
    fun `isDark 标记与实际亮度一致`() {
        // 标错的话，ReaderThemeScope 会按反方向派生面板配色（浅纸配深面板）
        ReaderTheme.ALL.forEach { t ->
            val dark = ReaderTheme.relLuminance(t.background) < 0.5
            assertEquals(dark, t.isDark, "${t.label} 的 isDark 标反了")
        }
    }

    @Test
    fun `id 不重复 —— 重复的话按 id 存取会认错主题`() {
        val ids = ReaderTheme.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "有重复 id: $ids")
    }

    @Test
    fun `自定义主题按纸色亮度自动判定明暗`() {
        assertTrue(ReaderTheme.custom(0xFF101010, 0xFFCCCCCC).isDark)
        assertTrue(!ReaderTheme.custom(0xFFF0F0F0, 0xFF222222).isDark)
    }

    @Test
    fun `自定义主题的页脚弱于正文、标题强于正文`() {
        val t = ReaderTheme.custom(0xFFF5EFDC, 0xFF3A3226)
        assertTrue(
            ReaderTheme.contrastRatio(t.background, t.footerColor) <
                ReaderTheme.contrastRatio(t.background, t.textColor)
        )
        assertTrue(
            ReaderTheme.contrastRatio(t.background, t.titleColor) >=
                ReaderTheme.contrastRatio(t.background, t.textColor)
        )
    }
}
