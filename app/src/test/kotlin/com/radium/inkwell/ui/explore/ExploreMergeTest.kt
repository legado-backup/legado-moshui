package com.radium.inkwell.ui.explore

import com.radium.inkwell.core.source.SearchResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ExploreMergeTest {

    private fun book(url: String, source: String = "s1") =
        SearchResult(title = url, bookUrl = url, sourceId = source)

    @Test
    fun `翻页错位带来的重复不会再进列表`() {
        val page1 = listOf(book("/a"), book("/b"), book("/c"))
        // 「最近更新」这类书单在两次翻页之间会往后挤：第 2 页开头就是第 1 页的末尾
        val page2 = listOf(book("/c"), book("/d"))
        assertEquals(listOf(book("/d")), newExploreItems(page1, page2))
    }

    @Test
    fun `同一页里重复列出的书只留第一本`() {
        val page = listOf(book("/a"), book("/a"), book("/b"))
        assertEquals(listOf(book("/a"), book("/b")), newExploreItems(emptyList(), page))
    }

    @Test
    fun `不同书源的同一地址不算重复`() {
        val current = listOf(book("/a", source = "s1"))
        assertEquals(listOf(book("/a", source = "s2")), newExploreItems(current, listOf(book("/a", source = "s2"))))
    }

    @Test
    fun `整页都是旧书时新增为空 —— 加载更多据此停下`() {
        val current = listOf(book("/a"), book("/b"))
        assertEquals(emptyList(), newExploreItems(current, current))
    }
}
