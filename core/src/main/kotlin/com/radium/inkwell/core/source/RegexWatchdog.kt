package com.radium.inkwell.core.source

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * 正则看门狗：把不可信的正则（书源规则、净化规则）放到守护线程上跑，调用方最多等 [DEADLINE_MS]。
 *
 * **为什么光有 [GuardedText] 不够**：那道关卡靠正则引擎回溯时反复取字符来检查预算，只在
 * 桌面 JVM 上成立 —— OpenJDK 的 Pattern 是纯 Java 实现，逐字符回调 CharSequence。
 * Android 的 java.util.regex 是 ICU 的壳：Matcher 一 reset 就 `input.toString()` 拷成 String
 * 交给原生代码去匹配，回溯全程不再碰我们的 CharSequence。关卡在真机上从来没生效过，
 * 单测跑在 JVM 上，所以一直是绿的。原生匹配也不理会线程中断，Android 又不支持 Thread.stop ——
 * **卡住的那条线程没有办法停下来**。
 *
 * 能做的只有两件事：
 * 1. 别让调用方陪着卡死。匹配挪到这里的线程上，调用方等到期限就放弃、按超时降级。
 *    阅读器是在互斥锁里跑净化的：从前一条病态规则卡住，此后所有章节的加载都跟着转圈。
 * 2. 别让同一条病态正则一章章地再卡死新线程。超时的 pattern 记进黑名单，本进程内再遇到
 *    直接按超时处理（Legado 碰到同样的事是直接重启应用）。卡住的线程会空转到进程结束，
 *    这是 Android 上躲不掉的代价；黑名单保证它至多一条正则一条线程，并把它降到最低优先级。
 *
 * 不换成 RE2 这类线性时间引擎：Legado 书源是照着 ICU 的语义写的（`\s` 匹配全角空格、
 * `\w` 匹配汉字，还大量用环视），换引擎等于悄悄改掉成千上万条规则的含义。
 *
 * 桌面 JVM 上 [GuardedText] 仍会先一步（[Purifier.BUDGET_MS]）掐断回溯、释放线程，
 * 所以期限比预算宽：只有真机上那条掐不断的路才会走到期限。
 */
internal object RegexWatchdog {

    /** 与 Legado 的替换超时同量级。正常的正则是毫秒级，这个数只会在病态规则上撞到 */
    const val DEADLINE_MS = 3_000L

    private val poisoned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 线程用完即还；空闲 30 秒回收。卡住的那条一直占着，不影响别的任务另起线程 */
    private val executor: ExecutorService = ThreadPoolExecutor(
        0, Int.MAX_VALUE, 30L, TimeUnit.SECONDS, SynchronousQueue(),
    ) { r -> Thread(r, "regex-watchdog").apply { isDaemon = true } }

    /** 本进程内超时过的 pattern：不再给它机会，调用方直接按超时降级 */
    fun isPoisoned(pattern: String): Boolean = pattern in poisoned

    /** 仅供测试：不必真等一次超时就能验证「黑名单里的直接降级」 */
    internal fun poison(pattern: String) {
        poisoned += pattern
    }

    /**
     * 在看门狗线程上跑 [block]，返回它的结果或原样抛出它的异常。
     * 到期仍未返回则抛 [RegexBudgetExceeded]，并把 [current]（此刻正在跑的那条 pattern ——
     * 一次跑多条规则时靠它认出元凶）拉进黑名单。
     */
    fun <T> run(
        current: () -> String?,
        deadlineMs: Long = DEADLINE_MS,
        block: () -> T,
    ): T {
        val worker = AtomicReference<Thread>()
        val future = executor.submit(
            Callable {
                // 线程是复用的：卡住之后又自己跑完的那条，会带着被降过的优先级回到池里
                Thread.currentThread().also {
                    it.priority = Thread.NORM_PRIORITY
                    worker.set(it)
                }
                block()
            },
        )
        try {
            return future.get(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            current()?.let { poisoned += it }
            future.cancel(true)
            // 停不下来，至少别跟前台抢 CPU
            worker.get()?.priority = Thread.MIN_PRIORITY
            throw RegexBudgetExceeded()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw RegexBudgetExceeded()
        }
    }
}
