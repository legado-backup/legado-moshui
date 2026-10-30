package com.radium.inkwell.core.source

import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cookie 存储按 RFC 6265 / OkHttp [Cookie.matches] 的语义取用，而不是按精确 host 分桶。
 * 反爬站「www. 种整站 cookie → 302 到 m.」那一套，按 host 分桶时 m. 永远拿不到 cookie，重定向循环到上限。
 */
class MemoryCookieJarTest {

    private fun save(jar: MemoryCookieJar, url: String, setCookie: String) {
        val u = url.toHttpUrl()
        jar.saveFromResponse(u, listOfNotNull(Cookie.parse(u, setCookie)))
    }

    private fun names(jar: MemoryCookieJar, url: String): List<String> =
        jar.loadForRequest(url.toHttpUrl()).map { "${it.name}=${it.value}" }

    @Test
    fun `整站 cookie 子域名之间共享，host-only 的不外溢`() {
        val jar = MemoryCookieJar()
        save(jar, "https://www.example.com/", "sid=1; Domain=.example.com; Path=/")
        save(jar, "https://www.example.com/", "local=2; Path=/")
        assertEquals(listOf("sid=1"), names(jar, "https://m.example.com/book"))
        assertEquals(listOf("sid=1"), names(jar, "https://example.com/"))
        assertEquals(listOf("sid=1", "local=2"), names(jar, "https://www.example.com/x"))
        assertTrue(names(jar, "https://other.com/").isEmpty())
    }

    @Test
    fun `路径与 secure 照规矩匹配`() {
        val jar = MemoryCookieJar()
        save(jar, "https://a.com/book/1", "p=1; Path=/book")
        save(jar, "https://a.com/", "s=1; Path=/; Secure")
        assertEquals(listOf("p=1", "s=1"), names(jar, "https://a.com/book/2"))
        assertEquals(listOf("s=1"), names(jar, "https://a.com/other"))
        assertTrue(names(jar, "http://a.com/other").isEmpty(), "secure cookie 不走明文")
    }

    @Test
    fun `同名同域同路径的后种替换先种，过期的 Set-Cookie 等于删除`() {
        val jar = MemoryCookieJar()
        save(jar, "https://a.com/", "t=old; Path=/")
        save(jar, "https://a.com/", "t=new; Path=/")
        assertEquals(listOf("t=new"), names(jar, "https://a.com/"))
        save(jar, "https://a.com/", "t=gone; Path=/; Max-Age=0")
        assertTrue(names(jar, "https://a.com/").isEmpty())
        assertEquals(0, jar.size)
    }

    @Test
    fun `宽搜索扇出几百个站也不会把早先的会话挤掉`() {
        val jar = MemoryCookieJar()
        save(jar, "https://first.com/", "sid=keep; Path=/")
        for (i in 0 until 300) save(jar, "https://site$i.com/", "c=$i; Path=/")
        assertEquals(listOf("sid=keep"), names(jar, "https://first.com/"))
    }

    @Test
    fun `总数封顶：先清过期的，再淘汰最早种下的`() {
        val jar = MemoryCookieJar(maxCookies = 3)
        val u: HttpUrl = "https://a.com/".toHttpUrl()
        val expiring = Cookie.Builder().name("e").value("1").domain("a.com")
            .expiresAt(System.currentTimeMillis() + 50).build()
        jar.saveFromResponse(u, listOf(expiring))
        save(jar, "https://a.com/", "a=1; Path=/")
        save(jar, "https://a.com/", "b=1; Path=/")
        Thread.sleep(80)
        save(jar, "https://a.com/", "c=1; Path=/")
        // 超出时过期的 e 先走，a 留下
        assertEquals(listOf("a=1", "b=1", "c=1"), names(jar, "https://a.com/"))
        save(jar, "https://a.com/", "d=1; Path=/")
        assertEquals(listOf("b=1", "c=1", "d=1"), names(jar, "https://a.com/"))
    }

    @Test
    fun `removeCookie 清掉会发给这个站的全部 cookie`() {
        val jar = MemoryCookieJar()
        save(jar, "https://www.example.com/", "sid=1; Domain=example.com; Path=/")
        save(jar, "https://www.example.com/", "local=2; Path=/")
        save(jar, "https://other.com/", "o=1; Path=/")
        jar.clear("https://www.example.com/".toHttpUrl())
        assertTrue(names(jar, "https://www.example.com/").isEmpty())
        assertEquals(listOf("o=1"), names(jar, "https://other.com/"))
    }
}
