package com.radium.inkwell.data.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfficialWebDavTest {

    @Test
    fun `空串回落到新 DAV 根`() {
        assertEquals(OfficialWebDav.DAV, OfficialWebDav.resolveDavUrl(""))
        assertEquals(OfficialWebDav.DAV, OfficialWebDav.resolveDavUrl("  "))
    }

    @Test
    fun `旧 api 主机改写成站点主机`() {
        assertEquals(OfficialWebDav.DAV, OfficialWebDav.resolveDavUrl(OfficialWebDav.LEGACY_DAV))
        assertEquals(
            OfficialWebDav.DAV,
            OfficialWebDav.resolveDavUrl("https://webdav-api.skylark.run/dav"),
        )
        assertTrue(OfficialWebDav.isLegacyDav(OfficialWebDav.LEGACY_DAV))
        assertTrue(OfficialWebDav.isLegacyDav("https://webdav-api.skylark.run/dav"))
        assertFalse(OfficialWebDav.isLegacyDav(""))
        assertFalse(OfficialWebDav.isLegacyDav(OfficialWebDav.DAV))
    }

    @Test
    fun `其它地址只补尾斜杠`() {
        assertEquals(
            "https://dav.jianguoyun.com/dav/",
            OfficialWebDav.resolveDavUrl("https://dav.jianguoyun.com/dav"),
        )
    }

    @Test
    fun `应用码名字按设备区分，不再与旧的共用名字相同`() {
        val a = OfficialWebDav.appPasswordName("1a2b3c4d-0000-0000-0000-000000000000", "Pixel 9")
        val b = OfficialWebDav.appPasswordName("9f8e7d6c-0000-0000-0000-000000000000", "Pixel 9")
        assertEquals("Inkwell Android · Pixel 9 · 1a2b3c4d", a)
        assertTrue(a != b)
        assertTrue(a != OfficialWebDav.APP_PASSWORD_NAME)
    }

    @Test
    fun `机型缺失时不留空段`() {
        assertEquals("Inkwell Android · 1a2b3c4d", OfficialWebDav.appPasswordName("1a2b3c4d", null))
        assertEquals("Inkwell Android · 1a2b3c4d", OfficialWebDav.appPasswordName("1a2b3c4d", "  "))
    }
}
