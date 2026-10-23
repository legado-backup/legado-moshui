package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.RhinoScriptRuntime
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 页面级规则（`:regex`、`<js>` 的 `result`）拿到的是**响应原文**，不是 Jsoup 重新序列化的 HTML。
 *
 * 上下文按引擎的 pageContext 搭：整页同时带着解析后的文档和原文。
 */
class PageContextTextTest {

    private val evaluator = RuleEvaluator(RhinoScriptRuntime())

    private fun pageCtx(body: String) = EvalContext(
        element = Jsoup.parse(body, "https://a.com/list"),
        json = body,
        baseUrl = "https://a.com/list",
    )

    private fun node(rule: String) = LegadoRuleAnalyzer.analyze(rule)

    @Test
    fun `JSON 接口页的脚本能直接 JSON parse result`() {
        val body = """{"list":[{"name":"第一章"},{"name":"第二章"}]}"""
        val rule = """<js>JSON.parse(result).list.length</js>"""
        assertEquals("2", evaluator.evalToString(node(rule), pageCtx(body)))
    }

    @Test
    fun `正则在原文上匹配，不带 Jsoup 转出来的实体`() {
        val body = """<html><body><h1 class='t'>A&B</h1></body></html>"""
        val rule = """:<h1 class='t'>([^<]*)"""
        // 从前匹配的是 outerHtml：引号被改成双引号（整条对不上），文本里的 & 变成 &amp;
        assertEquals("A&B", evaluator.evalToString(node(rule), pageCtx(body)))
    }

    @Test
    fun `元素上下文仍取 outerHtml —— 列表项没有原文可给`() {
        val doc = Jsoup.parse("""<ul><li data-id="7">甲</li></ul>""")
        val item = EvalContext(element = doc.selectFirst("li"), json = null, baseUrl = "https://a.com")
        assertEquals("7", evaluator.evalToString(node(""":data-id="(\d+)""""), item))
    }
}
