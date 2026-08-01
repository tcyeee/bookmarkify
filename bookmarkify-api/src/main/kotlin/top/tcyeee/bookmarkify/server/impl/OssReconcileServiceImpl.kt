package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.OssGovernanceConfig
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.config.entity.ScrapperConfig
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.OssReconcileReport
import top.tcyeee.bookmarkify.entity.entity.BackgroundImageEntity
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.UserInfoEntity
import top.tcyeee.bookmarkify.entity.enums.FileType
import top.tcyeee.bookmarkify.entity.enums.OssAddressing
import top.tcyeee.bookmarkify.entity.enums.OssObjectSource
import top.tcyeee.bookmarkify.entity.enums.OssObjectState
import top.tcyeee.bookmarkify.mapper.BackgroundImageMapper
import top.tcyeee.bookmarkify.mapper.OssObjectMapper
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IOssReconcileService
import top.tcyeee.bookmarkify.utils.OssUtils
import java.time.LocalDateTime

/**
 * OSS 对账实现，契约见 [IOssReconcileService]。
 *
 * 全程在内存里做集合运算，只在写回时按 chunk 批量更新 —— 桶里几万个对象逐个 update 会把
 * 一轮对账拖成几分钟的数据库风暴，而这些数据本来就该一次性拉进来比对。
 */
@Service
class OssReconcileServiceImpl(
    private val ossObjectMapper: OssObjectMapper,
    private val userMapper: UserMapper,
    private val backgroundImageMapper: BackgroundImageMapper,
    private val siteAssetMapper: SiteAssetMapper,
    private val scrapperConfig: ScrapperConfig,
    private val projectConfig: ProjectConfig,
    private val governanceConfig: OssGovernanceConfig,
) : IOssReconcileService {

    private companion object {
        /** IN 列表的分片大小。PostgreSQL 参数上限 65535，取 500 留足余量且仍然只有个位数轮次 */
        const val CHUNK = 500
    }

    override fun reconcile(): OssReconcileReport {
        val startedAt = System.currentTimeMillis()
        val prefixes = scannedPrefixes()
        val report = OssReconcileReport(scannedPrefixes = prefixes)

        runCatching {
            val now = LocalDateTime.now()

            // ── 1. 桶侧事实
            val bucket = prefixes.fold(LinkedHashMap<String, Long>()) { acc, prefix ->
                acc.apply { putAll(OssUtils.listAllObjects(prefix, governanceConfig.reconcileMaxKeys)) }
            }
            report.bucketObjects = bucket.size

            // ── 2. 账本现状。只关心落在已扫前缀内的行：前缀之外的行本轮没有桶侧证据，
            //     当成"桶里已不存在"去标 DELETED 会是纯粹的误判
            val ledger = ossObjectMapper.selectList(null)
                .filter { row -> prefixes.any { row.objectKey.startsWith(it) } }
            report.ledgerRowsBefore = ledger.size
            val ledgerKeys = ledger.map { it.objectKey }.toSet()

            // ── 3. 引用方事实
            val referenced = collectReferencedKeys()

            // ── 4. 桶里有、账本没有 → 补记
            val missingInLedger = bucket.keys - ledgerKeys
            missingInLedger.forEach { key ->
                runCatching {
                    ossObjectMapper.insertIgnore(
                        OssObjectEntity(
                            objectKey = key,
                            addressing = OssAddressing.LEGACY,
                            source = sourceOf(key),
                            size = bucket[key],
                            lastSeenAt = now,
                        )
                    )
                }.onFailure { log.warn("[OssReconcile] 补记失败(忽略): key={}, err={}", key, it.message) }
            }
            report.backfilled = missingInLedger.size

            // ── 5. 写回三类状态
            val presentKeys = bucket.keys intersect ledgerKeys
            updateInChunks(presentKeys) { set(OssObjectEntity::lastSeenAt, now) }

            val goneKeys = ledgerKeys - bucket.keys
            updateInChunks(goneKeys) { set(OssObjectEntity::state, OssObjectState.DELETED) }
            report.markedDeleted = goneKeys.size

            // 引用判定只针对**桶里确实存在**的对象；已经没了的没必要再分类
            val liveKeys = bucket.keys
            val referencedLive = liveKeys intersect referenced
            val orphanKeys = liveKeys - referenced

            updateInChunks(referencedLive) {
                set(OssObjectEntity::lastRefAt, now)
                set(OssObjectEntity::state, OssObjectState.ACTIVE)
            }
            updateInChunks(orphanKeys) { set(OssObjectEntity::state, OssObjectState.ORPHAN) }

            report.referenced = referencedLive.size
            report.orphans = orphanKeys.size
            report.orphanBytes = orphanKeys.sumOf { bucket[it] ?: 0L }

            // ── 6. 回收（默认关闭）
            report.reclaimed = reclaimOrphans(now)

            log.info(
                "[OssReconcile] 对账完成: prefixes={}, bucket={}, 补记={}, 已消失={}, 被引用={}, 孤儿={} ({} MB), 已回收={}",
                prefixes, bucket.size, report.backfilled, report.markedDeleted,
                report.referenced, report.orphans, report.orphanBytes / 1024 / 1024, report.reclaimed
            )
        }.onFailure {
            report.errorMsg = it.message ?: it::class.java.simpleName
            log.warn("[OssReconcile] 对账失败: err={}", it.message, it)
        }

        report.durationMs = System.currentTimeMillis() - startedAt
        return report
    }

    /**
     * 本服务管辖的全部 key 前缀。
     *
     * 只扫这些前缀是一条安全边界而不是优化：桶里若还有别的东西（人工上传的、别的项目共用的），
     * 不在名单里就永远不会被判成孤儿，更不会被回收。
     */
    private fun scannedPrefixes(): List<String> =
        (FileType.entries.map { it.folder } + scrapperConfig.keyPrefix)
            .map { it.trim().removeSuffix("/") }
            .filter { it.isNotEmpty() }
            .distinct()

    /** 按 key 前缀反推写入方，仅用于补记存量行时填一个合理值 */
    private fun sourceOf(key: String): OssObjectSource = when {
        key.startsWith(scrapperConfig.keyPrefix.removeSuffix("/")) -> OssObjectSource.SCRAPPER
        else -> OssObjectSource.USER_UPLOAD
    }

    /**
     * 扫遍**全部**引用方，收集"还有人要"的 key。
     *
     * 漏掉任何一处引用方，那一批对象就会被判成孤儿 —— 开启回收后即被删除。所以每加一个新的
     * 引用方，这个方法必须同步更新，这也正是 `FILE-SYSTEM-REFACTOR.md` D5 选择"对账"而不是
     * "维护 ref_count 列"的原因：漏改在这里只有一处，而计数列的漏改散落在每一条删除路径上。
     *
     * 当前的四处引用方：
     * 1. `user_info.avatar_file_id` —— 头像
     * 2. `background_image.file_id` —— 用户自传的背景图
     * 3. 配置里的系统默认背景图 —— **它们在库里没有任何行**，只能按约定拼出路径。漏掉这一处的
     *    后果是系统默认背景被当成孤儿删掉，而且没有任何用户数据能把它找回来
     * 4. `site_asset` —— 抓取落库的站点资产，`file_id` 优先、`storage_url` 兜底存量行
     *
     * **注意这里读的是引用方而不是 `user_file`。** `user_file` 已被 `oss_object` 取代且不再写入，
     * 拿它当引用来源会随时间越来越陈旧 —— 表现就是新上传的文件被判成孤儿。
     */
    private fun collectReferencedKeys(): Set<String> {
        val keys = HashSet<String>()
        val fileIds = HashSet<String>()

        userMapper.selectList(
            KtQueryWrapper(UserInfoEntity::class.java)
                .select(UserInfoEntity::avatarFileId)
                .isNotNull(UserInfoEntity::avatarFileId)
        ).forEach { it.avatarFileId?.takeIf(String::isNotBlank)?.let(fileIds::add) }

        backgroundImageMapper.selectList(
            KtQueryWrapper(BackgroundImageEntity::class.java).select(BackgroundImageEntity::fileId)
        ).forEach { it.fileId.takeIf(String::isNotBlank)?.let(fileIds::add) }

        siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .select(SiteAssetEntity::storageUrl, SiteAssetEntity::fileId)
        ).forEach { asset ->
            asset.fileId?.takeIf(String::isNotBlank)?.let(fileIds::add)
            // 存量的完整 URL 不是我方 key，signAsset 另有兼容路径处理，这里不参与对账
            asset.storageUrl?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("http://", true) && !it.startsWith("https://", true) }
                ?.let { keys += it.removePrefix("/").substringBefore("?") }
        }

        // 默认背景在库里没有行，按 defaultImgBacById 的同一套约定拼 key
        projectConfig.defaultBackgroundImage.forEach {
            keys += "${FileType.BACKGROUND.folder}/${it.substringBefore(".")}.png"
        }

        // file_id → object_key。分片查，避免引用数很大时拼出超长 IN 列表
        fileIds.chunked(CHUNK).forEach { chunk ->
            ossObjectMapper.selectList(
                KtQueryWrapper(OssObjectEntity::class.java).`in`(OssObjectEntity::id, chunk)
            ).forEach { keys += it.objectKey }
        }

        return keys
    }

    /**
     * 回收孤儿对象。**默认关闭**，见 [OssGovernanceConfig.reclaimOrphans]。
     *
     * 两道闸门缺一不可：
     * - `state = ORPHAN` —— 本轮对账刚判定过，不是历史遗留结论
     * - 入账与最后引用都早于宽限期 —— 挡住"scrapper 刚 PUT、API 还没落行"那个时序窗口，
     *   没有它，一次撞上对账的正常抓取会被当场清掉刚传上去的图
     */
    private fun reclaimOrphans(now: LocalDateTime): Int {
        if (!governanceConfig.reclaimOrphans) return 0
        val cutoff = now.minusDays(governanceConfig.orphanGraceDays)

        val candidates = ossObjectMapper.selectList(
            KtQueryWrapper(OssObjectEntity::class.java)
                .eq(OssObjectEntity::state, OssObjectState.ORPHAN)
                .lt(OssObjectEntity::createTime, cutoff)
                .and { w -> w.isNull(OssObjectEntity::lastRefAt).or().lt(OssObjectEntity::lastRefAt, cutoff) }
        )
        if (candidates.isEmpty()) return 0

        candidates.forEach { OssUtils.delete(it.objectKey) }
        updateInChunks(candidates.map { it.objectKey }) { set(OssObjectEntity::state, OssObjectState.DELETED) }

        log.info("[OssReconcile] 已回收孤儿对象: count={}, graceDays={}", candidates.size, governanceConfig.orphanGraceDays)
        return candidates.size
    }

    /** 按 key 分片批量更新，避免超长 IN 列表 */
    private fun updateInChunks(keys: Collection<String>, apply: KtUpdateWrapper<OssObjectEntity>.() -> Unit) {
        if (keys.isEmpty()) return
        keys.chunked(CHUNK).forEach { chunk ->
            val wrapper = KtUpdateWrapper(OssObjectEntity::class.java)
                .`in`(OssObjectEntity::objectKey, chunk)
                .apply(apply)
            runCatching { ossObjectMapper.update(null, wrapper) }
                .onFailure { log.warn("[OssReconcile] 批量更新失败(忽略): size={}, err={}", chunk.size, it.message) }
        }
    }
}
