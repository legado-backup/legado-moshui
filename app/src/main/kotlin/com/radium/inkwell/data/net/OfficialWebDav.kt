package com.radium.inkwell.data.net

/**
 * 开发者提供的 WebDAV 同步服务。
 *
 * 和 [InkwellServer] 同一写法：域名只写这一处。账号 API 仍在独立主机，
 * DAV 根走站点主机（`webdav.skylark.run/dav/`）。漏改一边会出现「能登录、同步 404」。
 *
 * DAV 根必须带尾斜杠，见 [com.radium.inkwell.core.webdav.WebDavClient] 的集合根注释。
 */
object OfficialWebDav {
    const val API = "https://webdav-api.skylark.run"
    const val SITE = "https://webdav.skylark.run"
    const val DAV = "https://webdav.skylark.run/dav/"

    /**
     * 0.3.1 及更早写入的 DAV 根。服务端把 DAV 从 api 主机挪到站点主机后，
     * 本地还躺着这条的用户会同步 404，启动时改写成 [DAV]。
     */
    const val LEGACY_DAV = "https://webdav-api.skylark.run/dav/"

    /**
     * 应用码备注前缀。0.3.7 及更早所有设备共用这**一个**名字，配上 replace 之后，
     * 第二台设备一登录就把第一台的码作废了，第一台此后每次同步都是 401 且没有提示。
     * 现在只作前缀和旧码识别用，真正发码的名字见 [appPasswordName]。
     */
    const val APP_PASSWORD_NAME = "Inkwell Android"

    /**
     * 每台设备各用一个名字，replace 只作废**本机**的旧码。
     * 带上机型是为了让用户在网页端的应用码列表里认得出哪条是哪台。
     */
    fun appPasswordName(deviceId: String, model: String?): String =
        listOf(APP_PASSWORD_NAME, model.orEmpty().trim().take(24), deviceId.take(8))
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    /**
     * 服务端 / 本地可能仍吐旧主机。空串回落到 [DAV]；旧根改写成新根；其余原样（补尾斜杠）。
     */
    fun resolveDavUrl(fromServer: String): String {
        val raw = fromServer.trim()
        val withSlash = when {
            raw.isEmpty() -> DAV
            raw.endsWith("/") -> raw
            else -> "$raw/"
        }
        return if (isLegacyDav(withSlash)) DAV else withSlash
    }

    fun isLegacyDav(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        return withSlash == LEGACY_DAV
    }
}
