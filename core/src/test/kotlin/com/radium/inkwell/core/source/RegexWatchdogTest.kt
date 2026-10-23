package com.radium.inkwell.core.source

import com.radium.inkwell.core.model.ContentElement
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 看门狗：真机（ICU）上 [GuardedText] 拦不住回溯，调用方只能靠它别陪着卡死。
 *
 * JVM 上的正则会回调 GuardedText，造不出「掐不断」的场景 —— 这里直接用一个不理会预算、
 * 也不理会中断的忙等来模拟 ICU 的原生匹配。黑名单是进程级的，各用例用各自的 pattern 名。
 */
class RegexWatchdogTest {

    private fun elapsedMs(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    @Test
    fun `掐不断的匹配到期即放手，并把元凶拉进黑名单`() {
        val release = AtomicBoolean(false)
        val pattern = "watchdog-test-stuck"
        try {
            val ms = elapsedMs {
                assertFailsWith<RegexBudgetExceeded> {
                    RegexWatchdog.run(current = { pattern }, deadlineMs = 200) {
                        @Suppress("ControlFlowWithEmptyBody")
                        while (!release.get()) { }
                    }
                }
            }
            assertTrue(ms < 2_000, "调用方应在期限附近放手，实测 ${ms}ms")
            assertTrue(RegexWatchdog.isPoisoned(pattern))
        } finally {
            release.set(true) // 放走那条忙等线程，别拖到测试进程结束
        }
    }

    @Test
    fun `正常结果与异常原样交回调用方`() {
        assertEquals(42, RegexWatchdog.run(current = { "watchdog-test-ok" }) { 42 })
        assertFailsWith<IllegalStateException> {
            RegexWatchdog.run(current = { "watchdog-test-throw" }) { error("坏替换串") }
        }
        assertFalse(RegexWatchdog.isPoisoned("watchdog-test-ok"))
        assertFalse(RegexWatchdog.isPoisoned("watchdog-test-throw"))
    }

    @Test
    fun `黑名单里的净化规则不再执行，整章直接按超时降级`() {
        val pattern = "watchdog-test-purify-[0-9]+"
        RegexWatchdog.poison(pattern)
        val elements = listOf(ContentElement.Paragraph("广告123正文"))
        val ms = elapsedMs {
            assertFailsWith<PurifyTimeoutException> {
                Purifier.lenient(listOf(PurifyRule(pattern, "", isRegex = true))).apply(elements)
            }
            assertFailsWith<PurifyTimeoutException> {
                Purifier.strict(listOf(PurifyRule(pattern, "", isRegex = true))).apply(elements)
            }
        }
        // 不再等一轮期限：再跑一次只会再钉死一条停不下来的线程
        assertTrue(ms < RegexWatchdog.DEADLINE_MS, "应立即降级，实测 ${ms}ms")
    }

    @Test
    fun `只有字面规则时不受黑名单影响`() {
        val elements = listOf(ContentElement.Paragraph("广告正文"))
        val out = Purifier.lenient(listOf(PurifyRule("广告", "", isRegex = false))).apply(elements)
        assertEquals(listOf(ContentElement.Paragraph("正文")), out)
    }

    @Test
    fun `黑名单里的书源正则按未匹配处理`() {
        val pattern = "watchdog-test-rule-(\\d+)"
        RegexWatchdog.poison(pattern)
        val evaluator = RuleEvaluator()
        val ctx = EvalContext(element = null, json = "第12章", baseUrl = "https://a.com")
        assertEquals(emptyList(), evaluator.evalToStrings(RuleNode.RegexRule(pattern), ctx))
        val replace = RuleNode.Pipe(
            source = RuleNode.Literal("第12章"),
            ops = listOf(PipeOp.RegexReplace(pattern, "")),
        )
        assertEquals(listOf("第12章"), evaluator.evalToStrings(replace, ctx))
    }
}
