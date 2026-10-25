package com.radium.inkwell.ui.bookshelf

import android.util.Log
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import java.util.concurrent.atomic.AtomicInteger

/**
 * 书架「进入一次」的耗时记录：冷启动、从设置/搜索/阅读返回，都会让书架整页重新组合一遍
 * （Navigation 3 把不可见的场景移出组合，返回时从零重建）。
 *
 * 为什么要量：从设置返回书架时，返回动画开头会冻住 0.3–0.5 秒再跳到终点（录屏逐帧数出来的）。
 * 读代码能指出几处嫌疑 —— 每个封面一层 BoxWithConstraints、首帧占位与图片两份都画、
 * 每张书卡每帧的位置回调 —— 但各占多少只能实测，凭猜去改很可能改错地方。
 *
 * 结果写进 logcat（tag = [TAG]），开发者模式的运行日志会录下来。一次进入只记一行，开销可忽略。
 * 时间统一用 System.nanoTime：withFrameNanos 给的帧时间也是这个时基。
 */
internal class ShelfEntryTrace {
    /** 书架这次组合开始的时刻 —— 在 BookshelfScreen 第一次组合时 remember 下来 */
    private val startNs = System.nanoTime()
    private var topBarMeasureNs = -1L
    private var gridMeasureNs = -1L

    // 下面两个 Modifier 每次进入只造一次（本对象在组合里 remember）：若每次重组现造，
    // 「只记第一次」的标记跟着重置，滚动、刷新的测量就会把首帧的数字盖掉

    /** 只记第一次测量：之后的滚动、刷新也会反复测量，那些不是「进入」的成本 */
    val topBarModifier: Modifier = firstMeasure { topBarMeasureNs = it }

    /**
     * 网格是懒加载的：可见的书卡在**网格测量期间**才组合、测量 ——
     * 所以这一项基本就是「首屏十几张书卡」的全部成本（含每个封面的 BoxWithConstraints 子组合）
     */
    val gridModifier: Modifier = firstMeasure { gridMeasureNs = it }

    private fun firstMeasure(record: (Long) -> Unit): Modifier {
        var done = false
        return Modifier.layout { measurable, constraints ->
            val t = System.nanoTime()
            val placeable = measurable.measure(constraints)
            if (!done) {
                done = true
                record(System.nanoTime() - t)
            }
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }

    /**
     * 在 LaunchedEffect 里调用：先等下一帧开头（= 首帧的主线程工作已经做完），
     * 再跟 [WATCH_MS] 内的每一帧，记下帧间隔 —— 返回动画就落在这一段里。
     */
    suspend fun report(visibleItems: () -> Int) {
        val firstFrameNs = withFrameNanos { it }
        val firstFrameMs = (firstFrameNs - startNs).ms()
        var prev = firstFrameNs
        var frames = 0
        var longest = 0L
        var slow = 0
        while (true) {
            val now = withFrameNanos { it }
            val gap = now - prev
            prev = now
            frames++
            longest = maxOf(longest, gap)
            if (gap > SLOW_FRAME_NS) slow++
            if (now - firstFrameNs >= WATCH_MS * 1_000_000) break
        }
        Log.i(
            TAG,
            "第 ${entries.incrementAndGet()} 次进入书架：首帧主线程 ${firstFrameMs}ms" +
                "（顶栏测量 ${topBarMeasureNs.ms()}ms，网格测量 ${gridMeasureNs.ms()}ms，" +
                "首屏 ${visibleItems()} 项）；之后 ${WATCH_MS}ms 内 $frames 帧，" +
                "最长间隔 ${longest.ms()}ms，超过 ${SLOW_FRAME_NS / 1_000_000}ms 的 $slow 帧",
        )
    }

    /** 保留一位小数：顶栏这类不到 1ms 的，取整就全成了 0。-1 表示这次没走到那一步（书架为空时没有网格） */
    private fun Long.ms(): String = if (this < 0) "-1" else "%.1f".format(this / 1_000_000.0)

    companion object {
        const val TAG = "ShelfPerf"
        private const val WATCH_MS = 700L
        /** 两个 60Hz 帧：超过它，肉眼就看得出顿了一下 */
        private const val SLOW_FRAME_NS = 32_000_000L

        /** 进程内第几次进入书架：第 1 次是冷启动，之后的是从别的页返回 */
        private val entries = AtomicInteger()
    }
}
