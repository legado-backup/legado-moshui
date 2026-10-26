package com.radium.inkwell.data.repo

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnedFilesTest {

    private val filesDir: File = Files.createTempDirectory("owned-test").toFile()
    private val covers = File(filesDir, "covers").apply { mkdirs() }
    private val db = File(filesDir, "inkwell.db").apply { writeText("db") }

    @Test
    fun `白名单目录里的文件照常删`() {
        val cover = File(covers, "a.img").apply { writeText("img") }
        assertTrue(deleteOwnedFile(cover.absolutePath, covers))
        assertFalse(cover.exists())
    }

    /** 同步来的书行把封面路径填成别处：绝对路径、`..` 绕出去，都不能删 */
    @Test
    fun `白名单之外的路径一律不碰`() {
        assertFalse(deleteOwnedFile(db.absolutePath, covers))
        assertFalse(deleteOwnedFile(File(covers, "../inkwell.db").path, covers))
        assertTrue(db.exists())
    }

    @Test
    fun `远程 URL 与空值直接跳过`() {
        assertFalse(deleteOwnedFile("https://ex.com/cover.jpg", covers))
        assertFalse(deleteOwnedFile(null, covers))
        assertFalse(deleteOwnedFile("", covers))
    }

    @Test
    fun `白名单目录本身不会被当成文件删掉`() {
        assertFalse(deleteOwnedFile(covers.absolutePath, covers))
        assertTrue(covers.isDirectory)
    }
}
