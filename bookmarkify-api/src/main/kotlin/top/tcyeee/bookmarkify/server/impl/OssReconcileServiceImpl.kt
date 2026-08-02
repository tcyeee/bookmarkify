package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.async.ParseLock
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
import java.time.Duration
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
    private val parseLock: ParseLock,
) : IOssReconcileService {

    private companion object {
        /** IN 列表的分片大小。PostgreSQL 参数上限 65535，取 500 留足余量且仍然只有个位数轮次 */
        const val CHUNK = 500

        /** 对账在 [ParseLock] 里的任务标识 */
        const val SWEEP_LABEL = "oss-reconcile"

        /** 锁的 TTL 要盖住一轮对账的最长耗时：全桶 ListObjects + 全量集合运算 + 分片写回 */
        val SWEEP_LOCK_TTL: Duration = Duration.ofMinutes(30)
    }

    /**
     * 一轮对账全程持 [ParseLock.sweep] 锁。
     *
     * 活性巡检早就这么做了，对账却一直没有 —— 而它比巡检更需要：巡检重复跑的代价是多 ping
     * 几次，对账重复跑的代价是**两轮同时在删对象**，且两轮看到的引用快照并不一致。
     *
     * 锁在这一层而不是在 `ScheduledTasks` 上，是为了把后台的手动触发
     * （`POST /admin/oss-object/reconcile`）也一并盖住：定时任务正在跑时有人点一下，
     * 或者两个人同时点，都是真实会发生的。
     *
     * 锁走 Redis 顺带解决了另一件事：这个系统跑着一个服务端实例，但开发机上任何一次
     * `bootRun` 都是**第二个实例**，连的是同一个库、同一个桶。没有这把锁，凌晨 4 点两边
     * 会各跑一轮。
     */
    override fun reconcile(): OssReconcileReport {
        val startedAt = System.currentTimeMillis()
        val prefixes = scannedPrefixes()
        val report = OssReconcileReport(scannedPrefixes = prefixes)

        val lockKey = ParseLock.sweep(SWEEP_LABEL)
        if (!parseLock.tryAcquire(lockKey, SWEEP_LOCK_TTL)) {
            log.warn("[OssReconcile] 上一轮对账仍在进行(或另一实例正在跑)，本轮跳过")
            report.errorMsg = "另一轮对账正在进行中"
            report.durationMs = System.currentTimeMillis() - startedAt
            return report
        }

        try {
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
            //
            // 每一步的写入是否**全部**成功都要记下来：任何一片失败都意味着库里的分类结果与
            // 本轮的真实判定不一致，而回收正是拿分类结果当依据的。见 [reclaimOrphans]。
            var classificationComplete = true

            val presentKeys = bucket.keys intersect ledgerKeys
            classificationComplete = updateInChunks(presentKeys) {
                set(OssObjectEntity::lastSeenAt, now)
            } && classificationComplete

            val goneKeys = ledgerKeys - bucket.keys
            classificationComplete = updateInChunks(goneKeys) {
                set(OssObjectEntity::state, OssObjectState.DELETED)
            } && classificationComplete
            report.markedDeleted = goneKeys.size

            // 引用判定只针对**桶里确实存在**的对象；已经没了的没必要再分类
            val liveKeys = bucket.keys
            val referencedLive = liveKeys intersect referenced
            val orphanKeys = liveKeys - referenced

            classificationComplete = updateInChunks(referencedLive) {
                set(OssObjectEntity::lastRefAt, now)
                set(OssObjectEntity::state, OssObjectState.ACTIVE)
            } && classificationComplete
            classificationComplete = updateInChunks(orphanKeys) {
                set(OssObjectEntity::state, OssObjectState.ORPHAN)
            } && classificationComplete

            report.referenced = referencedLive.size
            report.orphans = orphanKeys.size
            report.orphanBytes = orphanKeys.sumOf { bucket[it] ?: 0L }

            // ── 6. 回收（默认关闭）
            report.reclaimed = reclaimOrphans(now, orphanKeys, classificationComplete)

            log.info(
                "[OssReconcile] 对账完成: prefixes={}, bucket={}, 补记={}, 已消失={}, 被引用={}, 孤儿={} ({} MB), 已回收={}",
                prefixes, bucket.size, report.backfilled, report.markedDeleted,
                report.referenced, report.orphans, report.orphanBytes / 1024 / 1024, report.reclaimed
            )
        }.onFailure {
            report.errorMsg = it.message ?: it::class.java.simpleName
            log.warn("[OssReconcile] 对账失败: err={}", it.message, it)
        }
        } finally {
            parseLock.release(lockKey)
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
     * 三道闸门缺一不可：
     *
     * 1. **[classificationComplete]** —— 本轮的分类结果完整写回了库。
     * 2. **[orphanKeysThisRound]** —— 只回收本轮**在内存里判定**为孤儿的 key。
     * 3. **入账与最后引用都早于宽限期** —— 挡住"scrapper 刚 PUT、API 还没落行"那个时序窗口，
     *    没有它，一次撞上对账的正常抓取会被当场清掉刚传上去的图。
     *
     * 前两道是补上的，原先只有第三道加一句 `where state = ORPHAN`。那个写法有个要命的错位：
     * 它读的是**持久化状态**，而写这个状态的 [updateInChunks] 每一片失败都被吞掉只记 warn，
     * 流程照样往下走到删除。于是"本轮刚判定过"这条本该成立的前提并不成立 —— 拿到的可能是
     * 上一轮、甚至更早遗留的结论。一个早已入账、期间又被重新引用、而本轮 ACTIVE 回写恰好
     * 落在失败分片里的对象，会被直接删掉。
     *
     * 分类不完整时唯一正确的动作是**不删**：孤儿多留一天没有任何代价，删错的对象找不回来。
     */
    private fun reclaimOrphans(
        now: LocalDateTime,
        orphanKeysThisRound: Set<String>,
        classificationComplete: Boolean,
    ): Int {
        if (!governanceConfig.reclaimOrphans) return 0
        if (!classificationComplete) {
            log.warn("[OssReconcile] 本轮分类结果未能完整写回，跳过回收（孤儿多留一轮无代价，删错找不回来）")
            return 0
        }
        if (orphanKeysThisRound.isEmpty()) return 0
        val cutoff = now.minusDays(governanceConfig.orphanGraceDays)

        val candidates = ossObjectMapper.selectList(
            KtQueryWrapper(OssObjectEntity::class.java)
                .eq(OssObjectEntity::state, OssObjectState.ORPHAN)
                .lt(OssObjectEntity::createTime, cutoff)
                .and { w -> w.isNull(OssObjectEntity::lastRefAt).or().lt(OssObjectEntity::lastRefAt, cutoff) }
            // 与本轮的内存判定取交集：库里的 ORPHAN 可能是历史遗留结论，本轮判定才是当下的事实
        ).filter { it.objectKey in orphanKeysThisRound }
        if (candidates.isEmpty()) return 0

        candidates.forEach { OssUtils.delete(it.objectKey) }
        updateInChunks(candidates.map { it.objectKey }) { set(OssObjectEntity::state, OssObjectState.DELETED) }

        log.info(
            "[OssReconcile] 已回收孤儿对象: count={}, graceDays={}, keys={}",
            candidates.size, governanceConfig.orphanGraceDays, candidates.map { it.objectKey }
        )
        return candidates.size
    }

    /**
     * 按 key 分片批量更新，避免超长 IN 列表。
     *
     * @return 是否**全部**分片都写成功。调用方必须关心这个返回值：失败只记 warn 不抛异常是
     *   对的（一轮对账不该因为一片更新失败就整个作废），但"写了一半"与"全写完了"对回收来说
     *   是两种完全不同的前提，见 [reclaimOrphans]。
     */
    private fun updateInChunks(
        keys: Collection<String>,
        apply: KtUpdateWrapper<OssObjectEntity>.() -> Unit,
    ): Boolean {
        if (keys.isEmpty()) return true
        var allOk = true
        keys.chunked(CHUNK).forEach { chunk ->
            val wrapper = KtUpdateWrapper(OssObjectEntity::class.java)
                .`in`(OssObjectEntity::objectKey, chunk)
                .apply(apply)
            runCatching { ossObjectMapper.update(null, wrapper) }
                .onFailure {
                    allOk = false
                    log.warn("[OssReconcile] 批量更新失败(忽略): size={}, err={}", chunk.size, it.message)
                }
        }
        return allOk
    }
}
