package com.radium.inkwell.ui.bookshelf

import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.repo.BookRepository
import com.radium.inkwell.data.repo.bookKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 搜索 / 发现 / 预览用来认「已在书架」的 (书名, 作者) 键集合。
 *
 * 隐藏区上了锁（[AppPrefs.hiddenRequireAuth]）时，**隐藏的书一律不算在架**。从前这几处直接用
 * [BookRepository.shelfKeys]，它连隐藏的书一起算：搜一下书名，结果行上就亮着「已加入」、预览页写着
 * 「已在书架」—— 锁着的隐藏区就这么被一次搜索绕开了，等于替旁人确认「这本书藏在这儿」。
 *
 * 没上锁时照旧算在架：那种隐藏只是把书从列表里收起来，不防人，搜出来显示「已加入」才不让人困惑。
 */
internal fun searchableShelfKeys(
    books: List<BookEntity>,
    hiddenLocked: Boolean,
): Set<Pair<String, String>> =
    books.asSequence()
        .filter { !(hiddenLocked && it.hidden) }
        .mapTo(HashSet()) { bookKey(it.title, it.author) }

/** [searchableShelfKeys] 的实时版：书架变动、或隐藏区锁的开关一拨，标记都跟着变 */
internal fun searchableShelfKeys(
    bookRepo: BookRepository,
    appPrefs: AppPrefs,
): Flow<Set<Pair<String, String>>> =
    combine(bookRepo.books, appPrefs.hiddenRequireAuth) { books, locked ->
        searchableShelfKeys(books, locked)
    }
