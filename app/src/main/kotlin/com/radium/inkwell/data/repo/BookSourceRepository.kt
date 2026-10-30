package com.radium.inkwell.data.repo

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.radium.inkwell.core.source.BookSourceRule
import com.radium.inkwell.core.source.BookSourceTypes
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.dao.BookSourceHitDao
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.BookSourceListItem
import com.radium.inkwell.data.db.entity.CheckStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class BookSourceRepository(
    private val dao: BookSourceDao,
    private val hitDao: BookSourceHitDao,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 列表用投影，不含规则 JSON —— 见 [BookSourceListItem] */
    val sources: Flow<List<BookSourceListItem>> = dao.observeAll()

    data class ImportReport(
        val added: Int,
        val updated: Int,
        /** 转换阶段无法转换的书源原因 */
        val skipped: List<String>,
        /** 入库阶段失败的书源原因（正常应为空） */
        val failed: List<String> = emptyList(),
    ) {
        val imported: Int get() = added + updated

        /** 说清楚新增/更新/跳过/失败各多少，避免"看起来没反应" */
        val summary: String
            get() = buildString {
                when {
                    added > 0 && updated > 0 -> append("新增 $added 个、更新 $updated 个书源")
                    added > 0 -> append("新增 $added 个书源")
                    updated > 0 -> append("更新 $updated 个已有书源")
                    else -> append("没有可用书源")
                }
                if (failed.isNotEmpty()) append("；${failed.size} 个入库失败")
                if (skipped.isNotEmpty()) {
                    append("；跳过 ${skipped.size} 个")
                    // 按原因归类展示 Top3，便于定位（原因取冒号后的说明部分）
                    val byReason = skipped
                        .groupingBy { it.substringAfter(": ", it).take(24) }
                        .eachCount()
                        .entries.sortedByDescending { it.value }
                    append("（")
                    append(byReason.take(3).joinToString("，") { "${it.key}×${it.value}" })
                    if (byReason.size > 3) append("…")
                    append("）")
                }
            }
    }

    /**
     * 导入书源 JSON（Legado 原生格式，数组或单对象）。只收小说源（[BookSourceTypes.TEXT]）；
     * 音频/漫画/文件/视频源按类型过滤并计入 skipped。规则原文原样入库，运行期由引擎直接求值。
     */
    suspend fun importJson(text: String): Result<ImportReport> = withContext(Dispatchers.Default) {
        try {
            Result.success(importJsonInternal(text))
        } catch (e: CancellationException) {
            // 导入要写库（挂起）：用户离开页面是取消，别包成「导入失败」吞掉
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 解析整份 JSON、逐条 fromJson/toJson：书源合集动辄几 MB、几千条，在主线程上跑就是几秒冻屏
     * 乃至 ANR —— 调用方是 viewModelScope（Main.immediate），所以入口自己切到 Default。
     */
    private suspend fun importJsonInternal(text: String): ImportReport {
        val root = json.parseToJsonElement(text.trim())
        val objs = when (root) {
            is JsonArray -> root.toList()
            else -> listOf(root)
        }
        val rules = ArrayList<BookSourceRule>()
        val skipped = ArrayList<String>()
        objs.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val name = (obj["bookSourceName"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: (obj["bookSourceUrl"] as? JsonPrimitive)?.content ?: "未命名"
            // 先看原始字段：解析失败时也不要把漫画/听书源误当成默认文本灌进去
            val rawType = BookSourceTypes.parse(obj)
            if (!BookSourceTypes.isTextNovel(rawType)) {
                skipped += "$name: ${BookSourceTypes.unsupportedReason(rawType)}"
                return@forEach
            }
            val rule = runCatching { BookSourceRule.fromJson(obj.toString()) }.getOrNull()
            if (rule == null || rule.bookSourceUrl.isBlank()) {
                skipped += "$name: 解析失败或缺少 bookSourceUrl"
                return@forEach
            }
            // 解析后再挡一层：防序列化路径与手抠 JSON 不一致
            if (!BookSourceTypes.isTextNovel(rule.bookSourceType)) {
                skipped += "$name: ${BookSourceTypes.unsupportedReason(rule.bookSourceType)}"
                return@forEach
            }
            rules += rule
        }
        return upsertRules(rules, skipped)
    }

    /**
     * 批量入库：逐条构造 entity（失败只跳过该条），去重后单事务写入。
     * 单事务避免逐条写在真机上被中断只写进第一条的问题。
     */
    private suspend fun upsertRules(
        rules: List<BookSourceRule>,
        skipped: List<String> = emptyList(),
    ): ImportReport {
        // 同 id（= bookSourceUrl）保留最后一个，避免同事务重复主键
        val deduped = rules.associateBy { it.id }.values
        // 只按本次涉及的 id 逐条读旧行，禁止 getAll()+json 撑爆 CursorWindow
        val existingById = deduped.mapNotNull { rule ->
            loadEntity(rule.id)?.let { rule.id to it }
        }.toMap()
        val now = System.currentTimeMillis()

        val toWrite = ArrayList<BookSourceEntity>(deduped.size)
        val failed = ArrayList<String>()
        var added = 0
        var updated = 0
        deduped.forEach { rule ->
            try {
                val existing = existingById[rule.id]
                val ruleJson = rule.toJson()
                // 规则没变才留着旧的校验结论；规则一变，上一版的"可用"就不能给新规则背书了
                val keepCheck = existing?.takeIf { it.json == ruleJson }
                toWrite += BookSourceEntity(
                    id = rule.id,
                    name = rule.name,
                    // 重复导入保留用户的启用/禁用与排序
                    enabled = existing?.enabled ?: rule.enabled,
                    sortOrder = existing?.sortOrder ?: 0,
                    json = ruleJson,
                    // 同一份规则原样再导一遍不算修改：盖 now 的话，这一行会带着本机的旧启停/分组
                    // 在 WebDAV 合并里赢过别的设备刚做的修改（合并按 updatedAt 做 LWW）。
                    // 墓碑被重新导入则是「加回来」，必须盖新时间戳，否则删除会在同步里赢回去。
                    updatedAt = existing
                        ?.takeIf { !it.deleted && it.json == ruleJson && it.name == rule.name }
                        ?.updatedAt
                        ?: now,
                    groupName = importedGroup(existing, rule),
                    checkStatus = keepCheck?.checkStatus ?: CheckStatus.UNCHECKED,
                    checkMessage = keepCheck?.checkMessage.orEmpty(),
                    respondTime = keepCheck?.respondTime ?: -1,
                    checkedAt = keepCheck?.checkedAt ?: 0,
                    // 搜索测速同理：规则一变，旧规则攒下的快慢与连续失败就不作数了
                    searchMs = keepCheck?.searchMs ?: -1,
                    searchFails = keepCheck?.searchFails ?: 0,
                )
                if (existing == null) added++ else updated++
            } catch (e: Exception) {
                failed += "${rule.name}: ${e.message?.take(60)}"
            }
        }
        if (toWrite.isNotEmpty()) dao.upsertAll(toWrite)
        return ImportReport(added, updated, skipped, failed)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = dao.setEnabled(id, enabled, System.currentTimeMillis())

    suspend fun delete(id: String) {
        dao.softDeleteById(id, System.currentTimeMillis())
        // 换源记忆里指向这个源的行成了孤儿
        hitDao.deleteBySource(id)
    }

    /** 批量删除/启停：书源动辄几百个，逐条走会很慢也很吵 */
    suspend fun deleteAll(ids: Collection<String>) {
        if (ids.isEmpty()) return
        dao.softDeleteByIds(ids.toList(), System.currentTimeMillis())
        hitDao.deleteBySources(ids.toList())
    }

    suspend fun setEnabledAll(ids: Collection<String>, enabled: Boolean) {
        if (ids.isNotEmpty()) dao.setEnabledForIds(ids.toList(), enabled, System.currentTimeMillis())
    }

    suspend fun getRule(id: String): BookSourceRule? =
        loadEntity(id)?.let { runCatching { json.decodeFromString<BookSourceRule>(it.json) }.getOrNull() }

    suspend fun getEntity(id: String): BookSourceEntity? = loadEntity(id)

    /** 逐条解码几百份规则 JSON：搜索/发现/预览一进页面就调，别在主线程上做 */
    suspend fun getEnabledRules(): List<BookSourceRule> = withContext(Dispatchers.Default) {
        dao.getEnabledIds().mapNotNull { id -> getRule(id) }
    }

    /**
     * 启用的书源，**连校验结果一起带出来**。
     *
     * [getEnabledRules] 只给 rule，把 entity 上的 checkStatus/respondTime/sortOrder 全丢了 ——
     * 自动换源要靠这些决定先试谁（校验通过的、响应快的优先占住并发名额）。
     */
    suspend fun getEnabledForSwitch(): List<EnabledSource> = withContext(Dispatchers.Default) {
        dao.getEnabledIds().mapNotNull { id ->
            val entity = loadEntity(id) ?: return@mapNotNull null
            val rule = runCatching { json.decodeFromString<BookSourceRule>(entity.json) }
                .getOrNull() ?: return@mapNotNull null
            EnabledSource(
                rule = rule,
                checkStatus = entity.checkStatus,
                respondTime = entity.respondTime,
                sortOrder = entity.sortOrder,
                searchMs = entity.searchMs,
                searchFails = entity.searchFails,
            )
        }
    }

    data class EnabledSource(
        val rule: BookSourceRule,
        val checkStatus: Int,
        val respondTime: Long,
        val sortOrder: Int,
        /** 搜索测速，见 [BookSourceEntity.searchMs] / [BookSourceEntity.searchFails] */
        val searchMs: Long = -1,
        val searchFails: Int = 0,
    )

    /**
     * 写回一轮搜索测到的速度。逐条 UPDATE、不包事务：调用方只交「变化够大」的那些（见 SearchScheduling），
     * 一轮下来通常就几十条；而这个仓库不持有数据库句柄，单测靠假 DAO 跑，为它再引一个事务依赖不值。
     */
    suspend fun saveSearchStats(stats: Collection<EnabledSource>) {
        for (s in stats) dao.saveSearchStats(s.rule.id, s.searchMs, s.searchFails)
    }

    fun parseRule(text: String): Result<BookSourceRule> =
        runCatching { json.decodeFromString(text.trim()) }

    fun encodeRule(rule: BookSourceRule): String =
        json.encodeToString(BookSourceRule.serializer(), rule)

    // ---- 校验结果 / 排序 / 分组 ----

    suspend fun saveCheck(id: String, ok: Boolean, message: String, respondMs: Long) {
        dao.saveCheck(
            id = id,
            status = if (ok) CheckStatus.OK else CheckStatus.FAILED,
            message = message,
            respondTime = if (ok) respondMs else -1,
            checkedAt = System.currentTimeMillis(),
        )
    }

    /** 置顶：排到当前最小序号之前。递增分配（起点 = min - 个数），保持选中项之间的相对顺序 ——
     *  从前用 order-- 递减，会把多选置顶的顺序整体倒过来 */
    suspend fun moveToTop(ids: Collection<String>) {
        var order = (dao.minSortOrder() ?: 0) - ids.size
        val now = System.currentTimeMillis()
        ids.forEach { dao.setSortOrder(it, order++, now) }
    }

    suspend fun moveToBottom(ids: Collection<String>) {
        var order = (dao.maxSortOrder() ?: 0) + 1
        val now = System.currentTimeMillis()
        ids.forEach { dao.setSortOrder(it, order++, now) }
    }

    suspend fun setGroup(ids: Collection<String>, group: String) {
        dao.setGroupForIds(ids.toList(), group.trim(), System.currentTimeMillis())
    }

    /** 导出为 Legado 原生格式（库里存的就是原生 JSON） */
    suspend fun exportJson(ids: Collection<String>): String {
        val idSet = ids.toSet()
        val items = idSet.mapNotNull { id -> loadEntity(id)?.json }
        return items.joinToString(",\n", prefix = "[\n", postfix = "\n]")
    }

    /**
     * WebDAV / 全量备份：逐条取完整行。
     * 禁止一次性 `SELECT *` —— 源多或单源 JSON 大时 CursorWindow 直接崩。
     */
    suspend fun loadAllEntitiesIncludingDeleted(): List<BookSourceEntity> =
        dao.getAllIdsIncludingDeleted().mapNotNull { loadEntity(it) }

    /**
     * 单条读取；CursorWindow 仍可能因**单行**过大失败，吞掉以免整页列表陪葬。
     */
    /**
     * 重新导入时分组听谁的。
     *
     * 用户在书源管理里改过分组（库里的分组 ≠ 旧规则自带的分组），就保留用户的 ——
     * 从前一律用新规则的，重导一次合集，辛苦整理的分组全回到作者写的那个。
     * 没改过就跟着新规则走：作者在新版里调整了分组，用户理应拿到。
     */
    private fun importedGroup(existing: BookSourceEntity?, rule: BookSourceRule): String {
        if (existing == null || existing.deleted) return rule.group
        val oldRuleGroup = runCatching { BookSourceRule.fromJson(existing.json).group }.getOrNull()
            ?: return existing.groupName
        return if (existing.groupName == oldRuleGroup) rule.group else existing.groupName
    }

    private suspend fun loadEntity(id: String): BookSourceEntity? =
        try {
            dao.getById(id)
        } catch (e: CancellationException) {
            // CancellationException 本身就是 IllegalStateException 的子类：不先挑出来，取消也被下面吞成「读不出这一行」
            throw e
        } catch (_: IllegalStateException) {
            null
        }
}
