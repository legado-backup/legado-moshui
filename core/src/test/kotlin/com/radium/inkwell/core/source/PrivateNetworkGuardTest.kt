package com.radium.inkwell.core.source

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 书源 SSRF 防护。
 *
 * 测的是「解析结果」这一层，不是 URL 字符串 —— 域名解析到内网、以及 DNS 重绑定
 * （先给公网 IP 骗过校验、真连时再给内网）都只能在这里拦住。所以这里不打真实 DNS，
 * 而是塞一个假的 [Dns] 直接给出要考察的地址。
 */
class PrivateNetworkGuardTest {

    private fun fakeDns(vararg ips: String) = Dns { host ->
        ips.map { InetAddress.getByAddress(host, InetAddress.getByName(it).address) }
    }

    private fun guard(vararg ips: String, allowPrivate: Boolean = false) =
        PrivateNetworkGuardDns(fakeDns(*ips), allowPrivate)

    private fun assertBlocked(vararg ips: String) {
        assertFailsWith<UnknownHostException>("应拦截 ${ips.toList()}") {
            guard(*ips).lookup("evil.example.com")
        }
    }

    @Test
    fun `回环地址被拦`() {
        assertBlocked("127.0.0.1")
        assertBlocked("::1")
    }

    @Test
    fun `私有网段被拦`() {
        assertBlocked("10.0.0.1")
        assertBlocked("172.16.0.1")
        assertBlocked("192.168.1.1") // 家用路由器后台
    }

    @Test
    fun `链路本地与任意地址被拦`() {
        assertBlocked("169.254.169.254") // 云厂商元数据服务，SSRF 的经典目标
        assertBlocked("0.0.0.0")
    }

    @Test
    fun `运营商级 NAT 与 IPv6 唯一本地地址被拦`() {
        // 这两段 JDK 没有现成判定，是手写的，更要钉住
        assertBlocked("100.64.0.1")
        assertBlocked("100.127.255.255")
        assertBlocked("fd00::1")
        // 100.63 与 100.128 在网段之外，不该误伤
        assertEquals(1, guard("100.63.0.1").lookup("ok.example.com").size)
        assertEquals(1, guard("100.128.0.1").lookup("ok.example.com").size)
    }

    @Test
    fun `公网地址放行`() {
        assertEquals(1, guard("1.1.1.1").lookup("ok.example.com").size)
        assertEquals(1, guard("2001:4860:4860::8888").lookup("ok.example.com").size)
    }

    @Test
    fun `混合解析只把公网那几个交出去`() {
        // 一个域名同时解析到公网和内网时，若原样返回，OkHttp 仍可能连上内网那条
        val out = guard("1.1.1.1", "192.168.1.1").lookup("mixed.example.com")
        assertEquals(listOf("1.1.1.1"), out.map { it.hostAddress })
    }

    @Test
    fun `放行开关能整体关掉拦截 —— 测试跑在 127001 上要靠它`() {
        assertEquals(1, guard("127.0.0.1", allowPrivate = true).lookup("localhost").size)
    }

    // ---- 以下走真实的 OkHttp 连接：OkHttp 对 IP 字面量根本不调 Dns，光测 Dns 类看不出这条缝 ----

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        // 钉在 127.0.0.1：默认按 localhost 绑，有的机器会落到 ::1 上，下面写死的 IPv4 地址就连不上了
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private fun OkHttpClient.get(url: String) = newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun `IP 字面量的内网地址被拦，且一个请求都没发到服务端`() = withServer { server ->
        server.enqueue(MockResponse().setBody("secret"))
        val client = OkHttpClient.Builder().guardPrivateNetwork().build()
        assertFailsWith<UnknownHostException> { client.get("http://127.0.0.1:${server.port}/") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `单整数写法的 IPv4 同样被拦`() = withServer { server ->
        server.enqueue(MockResponse().setBody("secret"))
        val client = OkHttpClient.Builder().guardPrivateNetwork().build()
        // 2130706433 = 127.0.0.1：OkHttp 当 IP 直连，判定必须与它一致
        assertFailsWith<UnknownHostException> { client.get("http://2130706433:${server.port}/") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `网络层按真正连上的 IP 拦 —— 域名解析绕开了 Dns 拦截也挡得住`() = withServer { server ->
        server.enqueue(MockResponse().setBody("secret"))
        // 模拟「Dns 层被绕过」：解析器直接给内网地址，只靠网络拦截器兜底（重定向的每一跳走的也是这里）
        val client = OkHttpClient.Builder()
            .dns(fakeDns("127.0.0.1"))
            .addNetworkInterceptor(PrivateNetworkGuardInterceptor())
            .build()
        assertFailsWith<UnknownHostException> { client.get("http://public.example:${server.port}/") }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `放行开关下内网照常可达`() = withServer { server ->
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder().guardPrivateNetwork(allowPrivate = true).build()
        assertEquals(200, client.get("http://127.0.0.1:${server.port}/"))
    }

    // ---- 按主机判（WebView 渲染用：拿不到实际连上的 IP，只能事先判）----

    @Test
    fun `主机判定：内网字面量、localhost 与 local 不解析也拦`() {
        val noDns = Dns { error("字面量不该走 DNS: $it") }
        listOf(
            "127.0.0.1", "192.168.1.1", "10.0.0.8", "[::1]", "::1", "[fe80::1]",
            "2130706433", "localhost", "router.localhost", "nas.local",
        ).forEach { assertTrue(isPrivateNetworkHost(it, noDns), it) }
        assertFalse(isPrivateNetworkHost("8.8.8.8", noDns))
    }

    @Test
    fun `主机判定：域名任一解析结果落在内网就拦，解析失败不拦`() {
        assertTrue(isPrivateNetworkHost("evil.example.com", fakeDns("1.2.3.4", "192.168.0.1")))
        assertFalse(isPrivateNetworkHost("book.example.com", fakeDns("1.2.3.4")))
        val nx = Dns { throw UnknownHostException(it) }
        assertFalse(isPrivateNetworkHost("no-such.example.com", nx))
    }

    @Test
    fun `主线程判定只认字面量`() {
        assertTrue(isPrivateNetworkLiteral("192.168.1.1"))
        assertTrue(isPrivateNetworkLiteral("localhost"))
        // 域名不解析就判不了，交给 IO 线程上的那一道
        assertFalse(isPrivateNetworkLiteral("localtest.me"))
    }
}
