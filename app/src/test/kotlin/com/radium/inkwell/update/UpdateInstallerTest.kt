package com.radium.inkwell.update

import com.radium.inkwell.core.util.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateInstallerTest {

    private lateinit var server: MockWebServer
    private lateinit var installer: UpdateInstaller

    @BeforeTest
    fun setUp() {
        server = MockWebServer().also { it.start() }
        // JVM 单测里没有 PackageManager：包名/签名核对另测 isTrustedUpdateSigner，这里一律放行
        installer = UpdateInstaller(OkHttpClient()) {}
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

    /** sha256 对得上但包名/签名不对：同样删文件，绝不留给安装器 */
    @Test
    fun `authenticity failure throws and does not leave the file`() = runBlocking {
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
        val rejecting = UpdateInstaller(OkHttpClient()) { throw SecurityException("安装包签名与当前应用不一致") }
        assertFailsWith<SecurityException> { rejecting.downloadAndVerify(install, dir) {} }
        assertFalse(java.io.File(java.io.File(dir, "updates"), "inkwell.apk").exists())
    }

    /**
     * 离开页面（协程取消）要真的停下：从前阻塞读循环不看取消，整个包照样下完、留在 cache 里。
     * 服务端限速 64KB/100ms、共 2MB（全下完要 3 秒多），读到第一块就取消。
     */
    @Test
    fun `cancel stops the download and removes the partial file`() = runBlocking {
        val payload = ByteArray(2 * 1024 * 1024) { it.toByte() }
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write(payload))
                .throttleBody(64L * 1024, 100, TimeUnit.MILLISECONDS),
        )
        val dir = Files.createTempDirectory("upd").toFile()
        val install = DirectInstall(
            url = server.url("/inkwell.apk").toString(),
            sha256 = sha256(payload),
            size = payload.size.toLong(),
            filename = "inkwell.apk",
        )
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            installer.downloadAndVerify(install, dir) { started.complete(Unit) }
        }
        started.await()
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(job.isCancelled)
        assertTrue(elapsedMs < 2_000, "取消后应在下一块就停下，实际等了 ${elapsedMs}ms")
        assertFalse(java.io.File(java.io.File(dir, "updates"), "inkwell.apk").exists())
    }

    // ---------- 签名核对 ----------

    @Test
    fun `same signer is trusted`() {
        assertTrue(isTrustedUpdateSigner(setOf("aa"), setOf("aa"), setOf("aa")) { false })
    }

    @Test
    fun `different signer is rejected`() {
        assertFalse(isTrustedUpdateSigner(setOf("bb"), setOf("bb"), setOf("aa")) { false })
    }

    /** 新版本轮换了密钥（aa → bb）：证明链里有已装的 aa 就认 */
    @Test
    fun `rotated key with installed cert in lineage is trusted`() {
        assertTrue(isTrustedUpdateSigner(setOf("bb"), setOf("aa", "bb"), setOf("aa")) { false })
    }

    /** 新包用的是已装应用轮换链里的旧钥匙：交给已装那份的证明链判断 */
    @Test
    fun `past signer of the installed app is trusted`() {
        assertTrue(isTrustedUpdateSigner(setOf("aa"), setOf("aa"), setOf("bb")) { it == "aa" })
    }

    @Test
    fun `multiple signers must match exactly`() {
        assertTrue(isTrustedUpdateSigner(setOf("aa", "bb"), emptySet(), setOf("bb", "aa")) { false })
        assertFalse(isTrustedUpdateSigner(setOf("aa", "cc"), emptySet(), setOf("aa", "bb")) { true })
    }

    @Test
    fun `missing signatures are rejected`() {
        assertFalse(isTrustedUpdateSigner(emptySet(), emptySet(), setOf("aa")) { true })
    }
}
