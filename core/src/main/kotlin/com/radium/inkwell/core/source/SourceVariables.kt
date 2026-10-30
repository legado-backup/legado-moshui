package com.radium.inkwell.core.source

import kotlinx.serialization.json.Json

/**
 * 书源脚本变量表（`@put` / `java.put` 存下的那些）↔ JSON 文本。
 *
 * 章节级变量随 [RemoteChapter.variable] 落库，书级变量（[RemoteBookDetail.variables]）由 app 存在书行上，
 * 取目录 / 正文时再解出来交给 [BookSourceEngine.getToc] / [BookSourceEngine.getContent]。
 * 两头必须是同一种编码 —— 所以收在这一处，引擎和 app 都走它。
 */
object SourceVariables {
    private val json = Json

    /** 空表编成空串：绝大多数书源根本不存变量，库里别为它们写一堆 `{}` */
    fun encode(vars: Map<String, String>): String =
        if (vars.isEmpty()) "" else json.encodeToString(vars.toMap())

    /** 坏的 / 旧格式的文本当成空表：变量缺了顶多一条规则取空，不该让整章读不出来 */
    fun decode(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(text) }.getOrElse { emptyMap() }
    }
}
