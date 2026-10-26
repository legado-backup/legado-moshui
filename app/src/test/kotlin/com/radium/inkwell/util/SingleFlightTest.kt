package com.radium.inkwell.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SingleFlightTest {

    @Test
    fun `同一个 key 并发调用只执行一次`() = runTest {
        val flight = SingleFlight<String, Int>(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val a = async { flight.run("k") { runs++; gate.await(); 42 } }
        val b = async { flight.run("k") { runs++; gate.await(); 43 } }
        runCurrent()
        gate.complete(Unit)
        assertEquals(42, a.await())
        assertEquals(42, b.await())
        assertEquals(1, runs)
    }

    /** 邻章预载先发起、随后被取消，用户正等着的那次不能跟着白等一场 */
    @Test
    fun `发起者被取消、仍有人在等时继续执行`() = runTest {
        val flight = SingleFlight<String, Int>(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val first = launch { flight.run("k") { gate.await(); 1 } }
        val second = async { flight.run("k") { 2 } }
        runCurrent()
        first.cancelAndJoin()
        gate.complete(Unit)
        assertEquals(1, second.await())
    }

    /** 换源前 cancelAndJoin 预载：返回时那次抓取必须已经彻底停了，不能事后写回缓存 */
    @Test
    fun `最后一个等待者取消时，执行被取消且等它收尾`() = runTest {
        val flight = SingleFlight<String, Int>(StandardTestDispatcher(testScheduler))
        var cleanedUp = false
        val only = launch {
            flight.run("k") {
                try {
                    awaitCancellation()
                } finally {
                    cleanedUp = true
                }
            }
        }
        runCurrent()
        only.cancelAndJoin()
        assertTrue(cleanedUp)
    }

    @Test
    fun `失败交给所有等待者，下一次调用重新执行`() = runTest {
        val flight = SingleFlight<String, Int>(StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val a = async { runCatching { flight.run("k") { gate.await(); error("boom") } } }
        val b = async { runCatching { flight.run("k") { 0 } } }
        runCurrent()
        gate.complete(Unit)
        assertEquals("boom", a.await().exceptionOrNull()?.message)
        assertEquals("boom", b.await().exceptionOrNull()?.message)
        assertEquals(7, flight.run("k") { 7 })
        assertFailsWith<IllegalStateException> { flight.run("k") { error("again") } }
    }
}
