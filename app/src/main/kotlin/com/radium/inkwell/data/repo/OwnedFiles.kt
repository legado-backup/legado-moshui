package com.radium.inkwell.data.repo

import java.io.File

/**
 * 删一个「应该是我们自己写下的」文件 —— 只认 [roots] 之内的路径，别的一概不碰。
 *
 * 书行里的路径列并不全是本机写的：WebDAV 同步来的书行出自备份文件，谁能写备份谁就能填
 * （明文 http 下还有中间人）。拿它直接 `File(path).delete()`，一条构造过的封面路径加一条
 * 墓碑，就能删掉数据库、配置乃至别的书。先规范化（吃掉 `..` 与符号链接），再比前缀。
 *
 * 网络书的 coverPath 存的是远程 URL，规范化后落不进任何 root，自然跳过 —— 调用方不必再分类型。
 */
internal fun deleteOwnedFile(path: String?, vararg roots: File): Boolean {
    if (path.isNullOrBlank()) return false
    val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
    val owned = roots.any { root ->
        val r = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
        target != r && target.toPath().startsWith(r.toPath())
    }
    return owned && target.isFile && target.delete()
}
