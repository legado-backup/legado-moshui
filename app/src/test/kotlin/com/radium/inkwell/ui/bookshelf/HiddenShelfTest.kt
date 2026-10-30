package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.repo.bookKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 搜索/发现/预览认「已在书架」时，隐藏的书算不算。
 *
 * 起因：隐藏区上了锁，搜一下书名，结果行上照样亮着「已加入」、预览页写着「已在书架」，
 * 点「开始阅读」还不经验证直接打开 —— 一次搜索就绕开了那把锁。
 */
class HiddenShelfTest {

    private fun book(title: String, hidden: Boolean = false) = BookEntity(
        id = title,
        type = BookType.NET,
        title = title,
        author = "作者",
        addedAt = 0,
        updatedAt = 0,
        hidden = hidden,
    )

    private val shelf = listOf(book("看得见的书"), book("藏起来的书", hidden = true))

    @Test
    fun `上了锁 —— 隐藏的书不算在架`() {
        val keys = searchableShelfKeys(shelf, hiddenLocked = true)
        assertTrue(bookKey("看得见的书", "作者") in keys)
        assertFalse(bookKey("藏起来的书", "作者") in keys)
    }

    @Test
    fun `没上锁 —— 隐藏只是收起来，照旧算在架`() {
        val keys = searchableShelfKeys(shelf, hiddenLocked = false)
        assertEquals(setOf(bookKey("看得见的书", "作者"), bookKey("藏起来的书", "作者")), keys)
    }
}
