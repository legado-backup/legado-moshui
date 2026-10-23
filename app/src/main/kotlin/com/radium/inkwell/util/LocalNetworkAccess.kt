package com.radium.inkwell.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Android 17 的局域网权限（`ACCESS_LOCAL_NETWORK`，属「附近设备」权限组）。
 *
 * targetSdk 37 起，连局域网里的地址 —— 自备 WebDAV 最常见的就是家里 NAS 的 192.168.x.x、
 * `nas.local` —— 必须先拿到这个运行时权限。没拿到时系统**不报权限错**，TCP 连接只是挂到超时：
 * 用户看到「连接失败: timeout」，根本猜不到是权限的事。所以连之前先判一次，
 * 缺权限就当场申请；后台同步则直接给一句看得懂的错误，不干等超时。
 *
 * 只管 WebDAV 这一条「用户自己填地址」的路。书源本来就被拦在内网之外（PrivateNetworkGuard），
 * 那条路不需要、也不该借这个权限放行。注意 WebView 继承的是**应用**的权限状态 ——
 * 用户授权之后，书源渲染用的 WebView 也连得到局域网，那边得靠自己的拦截（WebViewPageRenderer）。
 */
class LocalNetworkAccess(private val context: Context) {

    /** 权限是否已具备。Android 17 以下没有这道闸，视为具备 */
    fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * 连 [url] 会不会被局域网权限挡住：缺权限、且目标落在局域网里。
     * 有权限时直接返回，不做 DNS —— 绝大多数人走这条，不该为它多付一次解析。
     */
    suspend fun isBlocked(url: String): Boolean =
        !isGranted() && withContext(Dispatchers.IO) { targetsLocalNetwork(url) }

    companion object {
        /** 只是个字符串常量；用到它的地方（[isGranted]、授权框）都在 Android 17 及以上才会走到 */
        @SuppressLint("InlinedApi")
        const val PERMISSION = Manifest.permission.ACCESS_LOCAL_NETWORK
    }
}

/** 缺局域网权限、连接注定超时时抛出；消息直接给用户看 */
class LocalNetworkPermissionMissing :
    Exception("这个 WebDAV 地址在局域网里，需要先授予「附近设备」权限")

/**
 * [url] 的主机是否落在系统定义的「局域网」里。
 *
 * 域名要解析一次看落点：路由器下发的 `nas.lan`、`mynas` 解析出来也是 192.168.x.x，
 * 系统同样按局域网拦。`.local` 不用解析 —— mDNS 解析本身就要这个权限，解析必然失败。
 * 解析失败就当不是：连接本身会以 DNS 错误失败，那个报错比「缺权限」更接近真相。
 */
internal fun targetsLocalNetwork(
    url: String,
    resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
): Boolean {
    val host = url.trim().toHttpUrlOrNull()?.host ?: return false
    if (host.endsWith(".local", ignoreCase = true)) return true
    val addresses = try {
        resolve(host)
    } catch (_: UnknownHostException) {
        return false
    } catch (_: SecurityException) {
        return false
    }
    return addresses.any { it.isLocalNetwork() }
}

/**
 * 按 Android「局域网」定义判地址：RFC1918、链路本地（169.254/16、fe80::/10）、
 * 运营商级 NAT（100.64/10）、多播与 IPv4 广播；IPv6 唯一本地地址 fc00::/7 也算进来
 * （家用路由器给内网发的就是它，官方「直连网段」的说法覆盖到它）。
 *
 * **回环不算**：127.0.0.1 不走 Wi-Fi/以太网接口，不在这道闸里。别照搬书源那套
 * `isPrivate()` —— 那边连回环也拦，口径不同。
 */
internal fun InetAddress.isLocalNetwork(): Boolean {
    val b = address
    return isSiteLocalAddress ||
        isLinkLocalAddress ||
        isMulticastAddress ||
        // 100.64.0.0/10
        (b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 64) ||
        // 255.255.255.255
        (b.size == 4 && b.all { it == 0xFF.toByte() }) ||
        // fc00::/7
        (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC)
}
