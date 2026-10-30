package com.radium.inkwell.ui.search

import com.radium.inkwell.core.source.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 搜索结果合并键 = 列表 key。
 *
 * 起因是个崩溃：列表按代表条目的 `sourceId|bookUrl` 做 key，合并却按书名+作者 ——
 * 一个书源把同一地址列成两本书名不同的书，就是两个 hit 撞同一个 key，「Key was already used」。
 */
class SearchMergeTest {

    private fun r(title: String, url: String, source: String = "s1", author: String? = "作者") =
        SearchResult(title = title, bookUrl = url, author = author, sourceId = source)

    private fun merge(vararg pages: List<SearchResult>): List<SearchHit> {
        val hits = LinkedHashMap<Pair<String, String>, SearchHit>()
        pages.forEach { mergeSearchResults(hits, it) }
        return hits.values.toList()
    }

    @Test
    fun `同一书源同一地址、书名不同 —— 两本书，key 互不相同`() {
        val hits = merge(listOf(r("书名", "/b/1"), r("书名（完本）", "/b/1")))
        assertEquals(2, hits.size)
        assertEquals(hits.size, hits.map { it.key }.toSet().size, "列表 key 必须唯一，否则 LazyColumn 崩溃")
    }

    @Test
    fun `同名同作者跨书源合并成一本`() {
        val hits = merge(listOf(r("书名", "/a", source = "s1")), listOf(r("书名", "/b", source = "s2")))
        assertEquals(1, hits.size)
        assertEquals(listOf("s1", "s2"), hits.single().results.map { it.sourceId })
    }

    @Test
    fun `书名作者首尾空白不影响合并`() {
        val hits = merge(listOf(r(" 书名 ", "/a", source = "s1", author = "作者 ")), listOf(r("书名", "/b", source = "s2")))
        assertEquals(1, hits.size)
        assertEquals("书名" to "作者", hits.single().key)
    }

    @Test
    fun `同一书源重复给同一本只留第一条`() {
        val hits = merge(listOf(r("书名", "/a"), r("书名", "/a2")))
        assertEquals(listOf("/a"), hits.single().results.map { it.bookUrl })
    }

    @Test
    fun `没有作者与空作者是同一本`() {
        val hits = merge(listOf(r("书名", "/a", source = "s1", author = null)), listOf(r("书名", "/b", source = "s2", author = "")))
        assertEquals(1, hits.size)
    }
}
