package com.radium.inkwell.core.source

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 书级变量随书落库、取目录 / 正文时再交回引擎。编码两头必须对得上，
 * 坏数据也只能退成空表 —— 变量缺了顶多一条 @get 取空，不该让整本书打不开。
 */
class SourceVariablesTest {

    @Test
    fun `round trip keeps keys and values`() {
        val vars = mapOf("bid" to "12345", "token" to "a\"b\\c", "中文" to "值")
        assertEquals(vars, SourceVariables.decode(SourceVariables.encode(vars)))
    }

    @Test
    fun `empty map encodes to empty string`() {
        assertEquals("", SourceVariables.encode(emptyMap()))
        assertEquals(emptyMap(), SourceVariables.decode(""))
    }

    @Test
    fun `corrupt text decodes to empty map`() {
        assertEquals(emptyMap(), SourceVariables.decode("{not json"))
        assertEquals(emptyMap(), SourceVariables.decode("[1,2]"))
    }
}
