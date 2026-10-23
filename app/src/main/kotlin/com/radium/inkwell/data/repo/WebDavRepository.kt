package com.radium.inkwell.data.repo

import java.io.File
import com.radium.inkwell.core.source.BookSourceTypes
import com.radium.inkwell.core.webdav.BackupBook
import com.radium.inkwell.core.webdav.BackupCodec
import com.radium.inkwell.core.webdav.BackupMerger
import com.radium.inkwell.core.webdav.BackupPayload
import com.radium.inkwell.core.webdav.BackupReplaceRule
import com.radium.inkwell.core.webdav.BackupRssSource
import com.radium.inkwell.core.webdav.BackupSource
import com.radium.inkwell.core.webdav.WebDavClient
import androidx.room3.withWriteTransaction
import com.radium.inkwell.data.db.InkwellDb
import com.radium.inkwell.data.db.dao.BookDao
import com.radium.inkwell.data.db.dao.BookSourceDao
import com.radium.inkwell.data.db.dao.ChapterDao
import com.radium.inkwell.data.db.entity.BookEntity
import com.radium.inkwell.data.db.entity.BookSourceEntity
import com.radium.inkwell.data.db.entity.BookType
import com.radium.inkwell.data.db.entity.ReplaceRuleEntity
import com.radium.inkwell.data.net.OfficialWebDav
import com.radium.inkwell.data.prefs.AppPrefs
import com.radium.inkwell.data.prefs.ReaderPrefs
import com.radium.inkwell.data.prefs.WebDavPrefs
import com.radium.inkwell.data.prefs.exportForBackup
import com.radium.inkwell.data.prefs.importFromBackup
import com.radium.inkwell.util.LocalNetworkAccess
import com.radium.inkwell.util.LocalNetworkPermissionMissing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class WebDavRepository(
    private val db: InkwellDb,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val sourceDao: BookSourceDao,
    private val rssDao: com.radium.inkwell.data.db.dao.RssSourceDao,
    private val cache: ChapterContentCache,
    private val prefs: WebDavPrefs,
    private val readerPrefs: ReaderPrefs,
    private val appPrefs: AppPrefs,
    private val replaceRules: ReplaceRuleRepository,
    private val localNetwork: LocalNetworkAccess,
) {
    private companion object {
        const val DIR = "inkwell"
        const val BACKUP = "inkwell/backup.json.gz"
        private val backupJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    /**
     * 同一时刻只跑一次同步。入口有三个（冷启动、退到后台、设置页手点），彼此不知情：
     * 启动即进设置页点「立即同步」就是两次 merge 加两次 PUT 并行 —— 后 PUT 的那份
     * 基于更旧的本地快照，会把前一份刚合并进来的远端更新又盖回去。
     */
    private val syncMutex = Mutex()

    suspend fun testConnection(url: String, username: String, password: String): Result<Unit> =
        WebDavClient(url, username, password).check()

    /** 双向同步：GET 远端 → 字段级 LWW 合并 → 写回本地 → PUT 合并结果 */
    suspend fun sync(): Result<String> = try {
        // 备份里装着每个书源的完整 JSON，几百个源就是几 MB：gzip、整份编解码、逐条解析书源类型
        // 都是重活，而设置页的「立即同步」是从 viewModelScope（主线程）调进来的
        withContext(Dispatchers.IO) {
            syncMutex.withLock { Result.success(syncLocked()) }
        }
    } catch (e: CancellationException) {
        // 退到后台的自动同步被进程回收、或用户离开设置页 —— 这是取消，不是「同步失败」
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun syncLocked(): String {
        val config = prefs.config.first()
        check(config.isConfigured) { "尚未配置 WebDAV" }
        val url = if (OfficialWebDav.isLegacyDav(config.url)) OfficialWebDav.DAV else config.url
        // 局域网 NAS 缺 Android 17 的局域网权限时，连接不报错、只会挂到超时 ——
        // 先拦下来，给一句看得懂的话（设置页据此弹授权）。后台自动同步也不必白等十几秒
        if (localNetwork.isBlocked(url)) throw LocalNetworkPermissionMissing()
        val client = WebDavClient(url, config.username, config.password)

        // 先建目录再读：首次同步时 inkwell/ 还不存在，直接 GET 备份文件，
        // 坚果云对「父目录不存在」回的是 409 而不是 404 —— 同步就一次都成不了。
        // MKCOL 是幂等的（已存在返回 405，客户端当成功）。
        client.mkcol(DIR)

        // 远端文件损坏（上传中断留半截、被别的客户端写坏）时 decode 会抛异常。若让它把整个 sync
        // 拖垮，PUT 永远执行不到 → 损坏文件永远修不好、同步永久瘫痪。这里把损坏视作「远端没有可用备份」，
        // 照常用本地覆盖上去，下一次同步就自愈了。
        val remote = client.get(BACKUP)?.let { runCatching { BackupCodec.decode(it) }.getOrNull() }
        // 本地快照在下载**之后**再拍：备份动辄几 MB，冷启动同步下载的那几秒里用户删书、隐藏、
        // 改分组，按下载前的快照合并会把这些修改原样写回去（删掉的书复活）。
        // 拍完到写回之间仍有一小段合并耗时，那段由 applyBooks / applySources 按时间戳兜住。
        val local = buildLocalPayload()

        val toUpload: BackupPayload
        var applied = 0
        if (remote == null) {
            toUpload = local
        } else {
            val merged = BackupMerger.merge(local, remote)
            applyToLocal(merged)
            applied = merged.changedBooks.size +
                merged.changedSources.size +
                merged.changedReplaceRules.size +
                merged.changedRssSources.size +
                listOfNotNull(merged.readerSettings, merged.appSettings).size
            toUpload = local.copy(
                books = merged.books,
                sources = merged.sources,
                replaceRules = merged.replaceRules,
                rssSources = merged.rssSources,
                readerSettings = merged.mergedReaderSettings,
                appSettings = merged.mergedAppSettings,
            )
        }

        // 直接 PUT 覆盖。半截文件的风险由上面的「decode 失败即当远端没有、直接覆盖自愈」兜底，
        // 不再用「PUT 临时名 + MOVE」——坚果云等服务器对 MOVE 回 409，反而把同步整个搞挂。
        client.put(BACKUP, BackupCodec.encode(toUpload), contentType = "application/gzip")
        prefs.markSynced(System.currentTimeMillis())
        return if (applied > 0) "同步完成，合并了 $applied 项远端更新" else "同步完成"
    }

    private suspend fun buildLocalPayload(): BackupPayload = BackupPayload(
        deviceId = prefs.deviceId(),
        exportedAt = System.currentTimeMillis(),
        // 试读未上架不进备份：从没成为用户书架上的书，同步出去只会在别的设备
        // 冒出一本看不见的书，或在用户点「不加入」之后变成凭空墓碑。
        // 墓碑仍要带走（inShelf 可能是 false —— 删过又试读过），合并靠它表达「删过」。
        books = bookDao.getAll().filter { it.inShelf || it.deleted }.map { b ->
            BackupBook(
                id = b.id, type = b.type, title = b.title, author = b.author,
                intro = b.intro,
                // coverPath 这个列名对两类书是两种东西：网络书存的是远程 URL（跨设备可用），
                // 本地书存的是本机绝对路径（换设备就是死路径）。只把前者带走。
                coverUrl = b.coverPath?.takeIf { b.type == BookType.NET },
                sourceId = b.sourceId, bookUrl = b.bookUrl, tocUrl = b.tocUrl,
                totalChapters = b.totalChapters,
                readChapterIndex = b.readChapterIndex, readCharOffset = b.readCharOffset,
                readAt = b.readAt, addedAt = b.addedAt, updatedAt = b.updatedAt,
                groupName = b.groupName,
                hidden = b.hidden,
                deleted = b.deleted,
            )
        },
        // 逐条取完整行：SELECT * 全表会把大 JSON 塞进 CursorWindow（~2MB）直接闪退
        sources = sourceDao.getAllIdsIncludingDeleted().mapNotNull { id ->
            runCatching { sourceDao.getById(id) }.getOrNull()
        }.map { s ->
            BackupSource(
                id = s.id, name = s.name, enabled = s.enabled, json = s.json,
                updatedAt = s.updatedAt,
                sortOrder = s.sortOrder,
                groupName = s.groupName,
                deleted = s.deleted,
            )
        },
        replaceRules = replaceRules.getAll().map { r ->
            BackupReplaceRule(
                id = r.id, name = r.name, pattern = r.pattern, replacement = r.replacement,
                isRegex = r.isRegex, scope = r.scope, bookId = r.bookId, enabled = r.enabled,
                sortOrder = r.sortOrder, updatedAt = r.updatedAt, deleted = r.deleted,
            )
        },
        rssSources = rssDao.getAll().map { r ->
            BackupRssSource(
                id = r.id, name = r.name, enabled = r.enabled, json = r.json,
                sourceJson = r.sourceJson, updatedAt = r.updatedAt, deleted = r.deleted,
            )
        },
        readerSettings = readerPrefs.exportForBackup(),
        appSettings = appPrefs.exportForBackup(),
    )

    private suspend fun applyToLocal(merged: BackupMerger.MergeResult) {
        // 另一台设备换过源的书：sourceId / bookUrl 跟本地对不上。目录表不进备份，本地留着的
        // 还是**旧源**的章节 URL，拿去配新源的规则抓正文必失败 → 触发自动换源 → 又换到第三个源、
        // updatedAt 刷新 → 下次同步反过来盖掉对面 —— 两台设备来回乒乒。本地换源
        // （NetBookRepository.changeSource）是「清目录 + 清缓存 + 改书行」一起做的，远端来的
        // 换源也得走同一条路：目录清空后，阅读页「目录为空则先拉一次」自然接管。
        // 缓存是文件 IO，放事务外先做；目录删除跟书行更新同一事务。
        val resourced = merged.changedBooks.filter { b ->
            val existing = bookDao.getById(b.id) ?: return@filter false
            // 元数据不会被采纳（本地在快照之后又改过，见 applyBooks）就谈不上换源
            existing.type == BookType.NET && b.updatedAt > existing.updatedAt &&
                (existing.sourceId != b.sourceId || existing.bookUrl != b.bookUrl)
        }.map { it.id }.toSet()
        if (resourced.isNotEmpty()) {
            withContext(Dispatchers.IO) { resourced.forEach { cache.clear(it) } }
        }
        // 别的设备删掉的书：本地删书（BookRepository.deleteBook）会顺手清掉导入的书文件、本地封面、
        // 正文缓存和目录行，同步过来的删除从前只打了墓碑 —— 这些东西就永远躺在磁盘上，没有任何入口能清。
        // 先记下要清的（事务里会改掉 deleted），目录行随事务删，文件 IO 放在事务之后
        val remotelyDeleted = merged.changedBooks.mapNotNull { b ->
            val existing = bookDao.getById(b.id) ?: return@mapNotNull null
            existing.takeIf { b.deleted && !it.deleted && b.updatedAt > it.updatedAt }
        }

        // 几十条逐行写 + 三张表：中途被杀不留半套。远端设置的导入是 DataStore，在事务外。
        db.withWriteTransaction {
            applyBooks(merged, resourced)
            applySources(merged)
            replaceRules.upsertAll(
                merged.changedReplaceRules.map { r ->
                    ReplaceRuleEntity(
                        id = r.id, name = r.name, pattern = r.pattern, replacement = r.replacement,
                        isRegex = r.isRegex, scope = r.scope, bookId = r.bookId, enabled = r.enabled,
                        sortOrder = r.sortOrder, updatedAt = r.updatedAt, deleted = r.deleted,
                    )
                }
            )
            rssDao.upsertAll(
                merged.changedRssSources.map { r ->
                    com.radium.inkwell.data.db.entity.RssSourceEntity(
                        id = r.id, name = r.name, enabled = r.enabled, json = r.json,
                        sourceJson = r.sourceJson, updatedAt = r.updatedAt, deleted = r.deleted,
                    )
                }
            )
        }
        if (remotelyDeleted.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                remotelyDeleted.forEach { book ->
                    book.localPath?.let { File(it).delete() }
                    // 网络书的 coverPath 存的是远程 URL，不是文件
                    if (book.type != BookType.NET) book.coverPath?.let { File(it).delete() }
                    cache.clear(book.id)
                }
            }
        }
        merged.readerSettings?.let { readerPrefs.importFromBackup(it) }
        merged.appSettings?.let { appPrefs.importFromBackup(it) }
    }

    private suspend fun applyBooks(merged: BackupMerger.MergeResult, resourced: Set<String>) {
        merged.changedBooks.forEach { b ->
            val existing = bookDao.getById(b.id)
            if (existing != null) {
                val sourceChanged = b.id in resourced
                val deletedRemotely = b.deleted && !existing.deleted && b.updatedAt > existing.updatedAt
                if (sourceChanged || deletedRemotely) chapterDao.deleteByBook(b.id)
                // 合并只在远端时间戳**严格更大**时才取远端那一份（相等算本地）。拿事务里的最新行再比一次：
                // 本地在拍快照之后又改过（时间戳已经比合并结果新），那一块就保留本地的 ——
                // 否则用户刚做的修改被合并结果里的旧值盖回去，还会随下一次上传扩散到所有设备。
                val takeMeta = b.updatedAt > existing.updatedAt
                val takeProgress = b.readAt > existing.readAt
                val meta = if (takeMeta) {
                    existing.copy(
                        title = b.title, author = b.author, intro = b.intro,
                        sourceId = b.sourceId, bookUrl = b.bookUrl, tocUrl = b.tocUrl,
                        updatedAt = b.updatedAt,
                        groupName = b.groupName, hidden = b.hidden,
                        deleted = b.deleted,
                        // 备份里的都是真正上过架的书（或墓碑）。本地若还是试读行，
                        // 被远端一份活书盖过来时必须上架，否则书架上永远看不见。
                        inShelf = !b.deleted,
                    )
                } else {
                    existing
                }
                bookDao.update(
                    meta.copy(
                        // 只补空，不覆盖：本地书这一列是有效的本机文件路径，
                        // 而远端对本地书永远给 null —— 拿 null 盖上去等于把封面弄丢。
                        coverPath = existing.coverPath ?: b.coverUrl,
                        // 换了源就按新源的章节数来，旧源的更大值对新目录没有意义
                        totalChapters = if (sourceChanged) b.totalChapters else maxOf(existing.totalChapters, b.totalChapters),
                        readChapterIndex = if (takeProgress) b.readChapterIndex else existing.readChapterIndex,
                        readCharOffset = if (takeProgress) b.readCharOffset else existing.readCharOffset,
                        readAt = if (takeProgress) b.readAt else existing.readAt,
                    )
                )
            } else {
                // 远端新增：本地占位（本地书缺文件时详情页提示重新导入；网络书可直接读）
                bookDao.upsert(
                    BookEntity(
                        id = b.id, type = b.type, title = b.title, author = b.author,
                        intro = b.intro, localPath = null, coverPath = b.coverUrl,
                        sourceId = b.sourceId, bookUrl = b.bookUrl, tocUrl = b.tocUrl,
                        totalChapters = b.totalChapters,
                        readChapterIndex = b.readChapterIndex, readCharOffset = b.readCharOffset,
                        readAt = b.readAt,
                        addedAt = if (b.addedAt > 0) b.addedAt else System.currentTimeMillis(),
                        updatedAt = b.updatedAt,
                        groupName = b.groupName, hidden = b.hidden,
                        // 墓碑也要落地：别把别的设备删掉的书当成新书插回来
                        deleted = b.deleted,
                        inShelf = !b.deleted,
                    )
                )
            }
        }
    }

    private suspend fun applySources(merged: BackupMerger.MergeResult) {
        merged.changedSources.forEach { s ->
            // 与 importJson 一致：远端若带了漫画/听书/视频源，别写进只跑小说引擎的本地库
            if (!isTextNovelSourceJson(s.json)) return@forEach
            // 校验结果（checkStatus/checkMessage/respondTime/checkedAt）是本地专属、不跨设备同步的列 ——
            // 整行 REPLACE 会把它们清空。读旧行、只覆盖同步字段（含分组），本地校验结果原样保留。
            val existing = sourceDao.getById(s.id)
            // 同 applyBooks：本地在拍快照之后又改过（启停/分组/删除），保留本地的
            if (existing != null && s.updatedAt <= existing.updatedAt) return@forEach
            sourceDao.upsert(
                (existing ?: BookSourceEntity(id = s.id, name = s.name, json = s.json, updatedAt = s.updatedAt))
                    .copy(
                        name = s.name, enabled = s.enabled, sortOrder = s.sortOrder,
                        json = s.json, updatedAt = s.updatedAt, groupName = s.groupName,
                        deleted = s.deleted,
                    )
            )
        }
    }

    /** 备份里的书源 JSON 是否为小说源；解析失败按「不是小说」丢掉，避免脏数据进库 */
    private fun isTextNovelSourceJson(json: String): Boolean = runCatching {
        val root = backupJson.parseToJsonElement(json)
        val obj = root as? JsonObject ?: root.jsonObject
        BookSourceTypes.isTextNovel(BookSourceTypes.parse(obj))
    }.getOrDefault(false)
}
