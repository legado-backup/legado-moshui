package com.radium.inkwell.core.source

import com.radium.inkwell.core.source.js.EngineJsHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 取消不能被翻译成「书源坏了」。
 *
 * 脚本里的 java.ajax 是同步桥，外层取消时只能交回空串，脚本随之「正常」跑完、规则抽不到内容。
 * 从前这一路以 SourceException 冒到阅读页：翻页取消了上一次加载，被当成「章节读不出来」，
 * 进而误触发自动换源、清缓存。
 */
class EngineCancellationTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val slowRequested = CountDownLatch(1)
    private val releaseSlow = CountDownLatch(1)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/chapter" -> MockResponse().setBody("<html><body><div id='c'>x</div></body></html>")
                "/slow" -> {
                    slowRequested.countDown()
                    releaseSlow.await(10, TimeUnit.SECONDS)
                    MockResponse().setBody("太迟了")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        base = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        releaseSlow.countDown()
        server.shutdown()
    }

    private fun http() = SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true)

    @Test
    fun `正文脚本的 ajax 途中被取消，调用方看到的是取消而不是书源错误`() = runBlocking {
        val engine = BookSourceEngine(
            http(),
            scriptRuntime = com.radium.inkwell.core.source.js.RhinoScriptRuntime(),
        )
        val source = BookSourceRule.fromJson(
            """
            {
              "bookSourceUrl": "$base",
              "bookSourceName": "取消测试",
              "ruleContent": { "content": "<js>java.ajax('$base/slow')</js>" }
            }
            """.trimIndent()
        )
        var outcome = "未结束"
        val job = launch(Dispatchers.Default) {
            try {
                engine.getContent(source, "$base/chapter", allowJsRender = false)
                outcome = "成功"
            } catch (e: CancellationException) {
                outcome = "取消"
                throw e
            } catch (e: Exception) {
                outcome = "错误: $e"
            }
        }
        assertTrue(slowRequested.await(10, TimeUnit.SECONDS), "脚本应已发出慢请求")
        job.cancel()
        job.join()
        assertEquals("取消", outcome)
    }

    @Test
    fun `外层已取消时 ajax 不再发请求`() = runBlocking {
        val jsHttp = EngineJsHttp(http())
        val cancelled = Job().apply { cancel() }
        val before = server.requestCount
        val resp = withContext(jsHttp.bindingTo(cancelled)) {
            jsHttp.fetch("$base/chapter", "GET", null, emptyMap())
        }
        assertNull(resp)
        assertEquals(before, server.requestCount)
    }
}
