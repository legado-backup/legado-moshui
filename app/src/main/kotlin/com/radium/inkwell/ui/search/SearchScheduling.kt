package com.radium.inkwell.ui.search

import com.radium.inkwell.data.db.entity.CheckStatus
import com.radium.inkwell.data.repo.BookSourceRepository.EnabledSource
import com.radium.inkwell.data.repo.TitleMatch
import kotlin.math.abs

/** 单个书源这一次搜索的结局；取消不算结局（不计入测速），由调用方原样往外抛 */
internal sealed interface SearchOutcome {
    data class Ok(val ms: Long) : SearchOutcome
    /** 等满了 [limitMs] 还没回来 */
    data class TimedOut(val limitMs: Long) : SearchOutcome
    /** 网络 / 规则出错 */
    data object Failed : SearchOutcome
}

/**
 * 搜索调度：先发谁、每个源等多久、测到的速度怎么记。抽成纯函数是为了能直接测。
 *
 * 一轮搜索的总耗时约等于「所有源耗时之和 ÷ 并发数」，真正拖时间的是坏源：一个吊死的源占着一个并发名额
 * 等满超时，几十个这样的源就是几分钟，健康的源只能排在后面干等。所以：
 * - 快的、活的先发（[order]）—— 最可能有结果的源先占住名额，结果早出来；
 * - 每个源按自己的历史定超时（[timeoutMs]），不再统一等 30 秒；
 * - 每次搜索都把耗时与成败记下来（[next]），用户不点「校验」也有数据可用。
 *
 * 测速口径只认搜索自己的 [EnabledSource.searchMs]；还没搜成功过的源才借校验的全链路耗时当估计 ——
 * 那是四个请求的总和，偏大，拿来定超时只会更宽松，不会误杀。
 */
internal object SearchScheduling {

    /** 连续失败这么多次就排到最后。不剔除：站点抽风过后会恢复，搜成功一次就清零回到正常位置 */
    const val DEMOTE_AFTER_FAILS = 2

    /** 没有任何测速数据的源按这个耗时排队：排在测过的快源之后、测过的慢源之前 */
    const val UNKNOWN_LATENCY_MS = 5_000L

    /** 没有测速数据时的超时。比从前统一的 30 秒短一半：真慢的源第一次超时后会被放宽，见 [next] */
    const val UNKNOWN_TIMEOUT_MS = 15_000L
    const val MIN_TIMEOUT_MS = 8_000L
    const val MAX_TIMEOUT_MS = 30_000L
    private const val TIMEOUT_FACTOR = 3

    /**
     * 提前结束：书名与关键词完全一致的结果已经来自这么多个书源，剩下的就先不搜了（界面上可「继续搜索」）。
     * 绝大多数搜索是奔着某一本书去的；五个源都有了，预览页换源也够用，再等几百个源回来只是让用户干等。
     */
    const val EARLY_STOP_SOURCES = 5

    /** 书源总共就这么几个时不提前结束：省不了多少时间，反倒让人以为没搜完 */
    const val EARLY_STOP_MIN_SOURCES = 20

    /** 排队顺序：没被降级的在前；同档按估计耗时从快到慢；再同则保持书源管理里的手动顺序（稳定排序） */
    fun order(sources: List<EnabledSource>): List<EnabledSource> = sources.sortedWith(
        compareBy<EnabledSource>({ demoted(it) }, { latencyEstimate(it) ?: UNKNOWN_LATENCY_MS }, { it.sortOrder }),
    )

    /** 这个源这次最多等多久：历史耗时的三倍，夹在 [MIN_TIMEOUT_MS]..[MAX_TIMEOUT_MS] 之间 */
    fun timeoutMs(source: EnabledSource?): Long {
        val estimate = source?.let(::latencyEstimate) ?: return UNKNOWN_TIMEOUT_MS
        return (estimate * TIMEOUT_FACTOR).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    /**
     * 记下一次结局后的测速。
     *
     * 超时不是「没有数据」：它说明这个源至少要这么久。把估计抬到不低于这次的上限，下回的超时就随之放宽
     * （8 秒超时 → 估计 8 秒 → 下回等 24 秒）。否则一个变慢了的源会每次都在同一个上限上超时，永远翻不了身。
     */
    fun next(source: EnabledSource, outcome: SearchOutcome): EnabledSource = when (outcome) {
        is SearchOutcome.Ok -> source.copy(
            // 滑动平均（新样本权重 0.3）：偶尔一次网络抖动不至于把一个快源打成慢源
            searchMs = if (source.searchMs < 0) outcome.ms else (source.searchMs * 7 + outcome.ms * 3) / 10,
            searchFails = 0,
        )
        is SearchOutcome.TimedOut -> source.copy(
            searchMs = maxOf(source.searchMs, outcome.limitMs),
            searchFails = (source.searchFails + 1).coerceAtMost(MAX_FAILS),
        )
        SearchOutcome.Failed -> source.copy(
            searchFails = (source.searchFails + 1).coerceAtMost(MAX_FAILS),
        )
    }

    /**
     * 值不值得写库。每轮几百个源，滑动平均每次都会挪一点；只有成败变了、或耗时变化超过一成（至少 200ms）
     * 才落库 —— 小抖动只活在这一轮的内存里，省掉成百上千次无意义的 UPDATE。
     */
    fun worthPersisting(before: EnabledSource, after: EnabledSource): Boolean {
        if (before.searchFails != after.searchFails) return true
        if (before.searchMs < 0) return after.searchMs >= 0
        return abs(after.searchMs - before.searchMs) >= maxOf(200L, before.searchMs / 10)
    }

    /** 书名与关键词归一化后完全一致（剥书名号与空白，同 [TitleMatch.normalize]） */
    fun isExactTitle(title: String, normalizedKeyword: String): Boolean =
        normalizedKeyword.isNotEmpty() && TitleMatch.normalize(title) == normalizedKeyword

    /**
     * 有搜索自己的数据就只信它：校验失败可能败在正文那一步，而搜索一直好好的，不该因此排到最后。
     * 从没搜过时才看校验结论。
     */
    private fun demoted(s: EnabledSource): Boolean =
        if (s.searchMs >= 0 || s.searchFails > 0) s.searchFails >= DEMOTE_AFTER_FAILS
        else s.checkStatus == CheckStatus.FAILED

    private fun latencyEstimate(s: EnabledSource): Long? = when {
        s.searchMs >= 0 -> s.searchMs
        s.checkStatus == CheckStatus.OK && s.respondTime > 0 -> s.respondTime
        else -> null
    }

    private const val MAX_FAILS = 99
}
