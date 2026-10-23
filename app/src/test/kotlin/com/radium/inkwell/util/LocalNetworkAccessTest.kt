package com.radium.inkwell.util

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNetworkAccessTest {

    private fun ip(s: String) = InetAddress.getByName(s)

    /** 测试里不碰真 DNS：域名一律按给定表解析，表里没有就当解析失败 */
    private fun resolver(vararg pairs: Pair<String, String>): (String) -> List<InetAddress> {
        val table = pairs.toMap()
        return { host ->
            table[host]?.let { listOf(ip(it)) }
                ?: if (host.any { it.isLetter() } && ':' !in host) throw UnknownHostException(host)
                else listOf(ip(host))
        }
    }

    @Test
    fun `家用网段与 CGNAT、链路本地都算局域网`() {
        listOf(
            "192.168.1.10", "10.0.0.2", "172.16.5.1", "172.31.255.254",
            "169.254.1.1", "100.64.0.1", "100.127.255.255",
            "224.0.0.251", "255.255.255.255",
            "fe80::1", "fd12:3456::1",
        ).forEach { assertTrue(ip(it).isLocalNetwork(), it) }
    }

    @Test
    fun `公网与回环不算 —— 回环不走 Wi-Fi 接口，和书源那套 isPrivate 口径不同`() {
        listOf(
            "8.8.8.8", "172.32.0.1", "100.128.0.1", "100.63.255.255",
            "127.0.0.1", "::1", "2001:4860:4860::8888",
        ).forEach { assertFalse(ip(it).isLocalNetwork(), it) }
    }

    @Test
    fun `按 URL 主机判定，域名看解析落点`() {
        val r = resolver("nas.lan" to "192.168.1.5", "dav.jianguoyun.com" to "8.8.8.8")
        assertTrue(targetsLocalNetwork("http://192.168.1.5:5005/dav/", r))
        assertTrue(targetsLocalNetwork("  http://nas.lan/webdav  ", r))
        assertTrue(targetsLocalNetwork("http://[fe80::1]:8080/", r))
        assertFalse(targetsLocalNetwork("https://dav.jianguoyun.com/dav/", r))
    }

    @Test
    fun `local 后缀不解析也算 —— mDNS 解析本身就要这个权限`() {
        val r: (String) -> List<InetAddress> = { error("不该解析 $it") }
        assertTrue(targetsLocalNetwork("http://synology.local:5005/", r))
    }

    @Test
    fun `解析失败或地址不合法时不拦，让连接自己报真实错误`() {
        val r = resolver()
        assertFalse(targetsLocalNetwork("https://no-such-host.example/", r))
        assertFalse(targetsLocalNetwork("not a url", r))
        assertFalse(targetsLocalNetwork("", r))
    }
}
