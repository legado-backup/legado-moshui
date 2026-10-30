package com.radium.inkwell

import android.content.Context
import android.util.Log
import com.radium.inkwell.data.repo.BookRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * Baseline Profile 生成器走进阅读器的夹具。
 *
 * 生成器跑在另一个进程，空书架走不进 [Paginator]/[ReaderScreen]。
 * 启动时带 [EXTRA] 就把 assets 里那本固定 txt 导入书架，测试再点书名进去。
 * 用户正常启动不会带这个 extra。
 *
 * **只在生成器用的变体里生效**（[enabled]，开关见 res/values/profile_seed.xml 与 app/build.gradle.kts）。
 * 桌面 alias 是 exported 的，任何应用都能带着这个 extra 拉起我们：正式包也认的话，谁都能往用户书架上
 * 塞一本「Benchmark」，再经 WebDAV 同步扩散到用户所有设备。
 */
internal object ProfileSeed {
    const val EXTRA = "com.radium.inkwell.seed_profile_book"
    const val TITLE = "Benchmark"
    private const val TAG = "ProfileSeed"

    /** 这个变体认不认夹具 extra。正式包 / 调试包恒为 false */
    fun enabled(context: Context): Boolean = context.resources.getBoolean(R.bool.profile_seed_enabled)

    /**
     * 导入失败只记日志、不往外抛：调用方是 onCreate 里的 lifecycleScope.launch，抛出去就是整个应用闪退。
     * 生成器那边等不到书名自会报清楚的错（见 BaselineProfileGenerator 的 tap）。
     */
    suspend fun importIfRequested(context: Context, requested: Boolean) {
        if (!requested || !enabled(context)) return
        val repo = GlobalContext.get().get<BookRepository>()
        try {
            if (repo.shelfLocalBookIdByKey(TITLE, "") != null) return
            val tmp = File(context.cacheDir, "benchmark-seed.txt")
            try {
                withContext(Dispatchers.IO) {
                    context.assets.open("benchmark/seed.txt").use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                }
                repo.importLocalFile(tmp, "$TITLE.txt").getOrThrow()
            } finally {
                tmp.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "夹具书导入失败", e)
        }
    }
}
