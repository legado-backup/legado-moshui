package com.radium.inkwell.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * 同一个 key 的并发调用合并成**一次**执行，所有调用方等同一个结果（成功或异常都一样）。
 *
 * 取消语义按「还有没有人在等」算，而不是跟着第一个调用方走：
 * - 发起它的那个调用方被取消了、但还有别人在等 —— 继续跑，别让后来的人白等一场再重来；
 * - **最后一个**等待者也走了 —— 取消它，并且**等它真停下**再返回。调用方 cancelAndJoin 自己时，
 *   拿到的承诺仍是「这件事已经彻底停了」（换源前清缓存就靠这个：不能有一次旧抓取在之后写回来）。
 *
 * 执行跑在自己的作用域（[context] + SupervisorJob）里而不是调用方的协程里，所以不继承调用方的
 * 上下文元素。SupervisorJob 是必须的：一次执行失败只交给等它的人，不能连坐把作用域拖死、
 * 之后的每次调用都直接失败。
 */
class SingleFlight<K : Any, V>(context: CoroutineContext) {

    private val scope = CoroutineScope(SupervisorJob() + context)

    private class Call<V>(val deferred: Deferred<V>) {
        var waiters = 0
    }

    private val calls = HashMap<K, Call<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val call = synchronized(calls) {
            calls.getOrPut(key) { Call(scope.async { block() }) }.also { it.waiters++ }
        }
        var last = false
        try {
            return call.deferred.await()
        } finally {
            synchronized(calls) {
                call.waiters--
                if (call.waiters == 0) {
                    if (calls[key] === call) calls.remove(key)
                    last = true
                }
            }
            // 已完成的 cancel 是空操作；没完成就是「没人要了」，掐掉并等它收尾
            if (last && !call.deferred.isCompleted) {
                withContext(NonCancellable) { call.deferred.cancelAndJoin() }
            }
        }
    }
}
