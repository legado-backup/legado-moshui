package com.radium.inkwell.update

import com.radium.inkwell.core.util.toHex
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class UpdateInstallerTest {

    private lateinit var server: MockWebServer
    private lateinit var installer: UpdateInstaller

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        installer = UpdateInstaller(OkHttpClient())
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    @Test
    fun `download verifies matching sha256 and writes the file`() = runBlocking {
        val text = "pretend-apk-bytes-content"
        server.enqueue(MockResponse().setBody(text))
        val dir = Files.createTempDirectory("upd").toFile()
        val payload = text.toByteArray()
        val install = DirectInstall(
            url = server.url("/inkwell.apk").toString(),
            sha256 = sha256(payload),
            size = payload.size.toLong(),
            filename = "inkwell.apk",
        )
        var lastProgress = 0f
        val out = installer.downloadAndVerify(install, dir) { lastProgress = it }
        assertEquals(text, out.readText())
        assertEquals(1f, lastProgress)
    }

    /** 服务端给的文件名只取最后一段：带 `../` 的不能跳出 updates/ 去覆盖别的私有文件 */
    @Test
    fun `filename with path segments stays inside the updates dir`() = runBlocking {
        val text = "pretend-apk-bytes-content"
        server.enqueue(MockResponse().setBody(text))
        val dir = Files.createTempDirectory("upd").toFile()
        val victim = java.io.File(dir, "victim.db").apply { writeText("keep me") }
        val payload = text.toByteArray()
        val install = DirectInstall(
            url = server.url("/inkwell.apk").toString(),
            sha256 = sha256(payload),
            size = payload.size.toLong(),
            filename = "../victim.db",
        )
        val out = installer.downloadAndVerify(install, dir) {}
        assertEquals(java.io.File(dir, "updates").canonicalPath, out.parentFile.canonicalPath)
        assertEquals("keep me", victim.readText())
    }

    @Test
    fun `sha256 mismatch throws and does not leave the file`() = runBlocking {
        val text = "pretend-apk-bytes-content"
        server.enqueue(MockResponse().setBody(text))
        val dir = Files.createTempDirectory("upd").toFile()
        val install = DirectInstall(
            url = server.url("/inkwell.apk").toString(),
            sha256 = "deadbeef",
            size = text.toByteArray().size.toLong(),
            filename = "inkwell.apk",
        )
        assertFailsWith<IOException> { installer.downloadAndVerify(install, dir) {} }
        assertFalse(java.io.File(java.io.File(dir, "updates"), "inkwell.apk").exists())
    }
}
