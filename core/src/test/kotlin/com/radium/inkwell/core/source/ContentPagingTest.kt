package com.radium.inkwell.core.source

import com.radium.inkwell.core.model.ContentElement
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals

/** 正文翻页的收尾，以及 `@html` 正文的分段 */
class ContentPagingTest {

    private lateinit var server: MockWebServer
    private lateinit var base: String
    private val hits = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                hits.merge(path.substringBefore('#'), 1, Int::plus)
                val body = when (path) {
                    "/c/1.html" -> page("第一页", "/c/1_2.html")
                    "/c/1_2.html" -> page("第二页", "javascript:void(0)")
                    "/d/1.html" -> page("只有一页", "#")
                    "/e/1.html" -> "<html><body><div id=\"content\">\n　　段一\n　　段二\n</div></body></html>"
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        base = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun page(text: String, next: String) =
        "<html><body><div id=\"content\"><p>$text</p></div><a id=\"next\" href=\"$next\">下一页</a></body></html>"

    private val engine by lazy {
        BookSourceEngine(SourceHttpClient(OkHttpClient(), retryBaseDelayMs = 1, allowPrivateNetwork = true))
    }

    private val source by lazy {
        BookSourceRule.fromJson(
            """
            {
              "bookSourceUrl": "$base",
              "bookSourceName": "翻页测试",
              "ruleContent": { "content": "id.content@html", "nextContentUrl": "id.next@href" }
            }
            """.trimIndent()
        )
    }

    private fun texts(url: String): List<String> = runBlocking {
        engine.getContent(source, "$base$url", allowJsRender = false).elements
            .filterIsInstance<ContentElement.Paragraph>()
            .map { it.text }
    }

    @Test
    fun `末页的下一页是 javascript 链接时正常收尾，前面的页不作废`() {
        assertEquals(listOf("第一页", "第二页"), texts("/c/1.html"))
    }

    @Test
    fun `末页的下一页是井号时不会把同一页再抓一遍`() {
        assertEquals(listOf("只有一页"), texts("/d/1.html"))
        assertEquals(1, hits["/d/1.html"])
    }

    @Test
    fun `html 规则取到的换行加缩进正文按段切分`() {
        assertEquals(listOf("段一", "段二"), texts("/e/1.html"))
    }
}
