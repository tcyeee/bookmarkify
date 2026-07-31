package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import top.tcyeee.bookmarkify.config.entity.ScrapperConfig
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.SitePageMetaEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.mapper.ScrapeSnapshotMapper
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import top.tcyeee.bookmarkify.mapper.SitePageMetaMapper
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 把一次抓取结果落库。
 *
 * 只写 `scrape_snapshot` / `site_page_meta` / `site_asset` 三张表，**绝不触碰
 * `site_display_pref`** —— 那是人工调好的展示偏好，重抓不该把它冲掉。这条边界正是旧
 * `bookmark_logo` 把抓取事实与人工偏好混在一张表所缺失的。
 */
@Service
class SiteAssetWriter(
    private val scrapeSnapshotMapper: ScrapeSnapshotMapper,
    private val sitePageMetaMapper: SitePageMetaMapper,
    private val siteAssetMapper: SiteAssetMapper,
    private val objectMapper: ObjectMapper,
    private val scrapperConfig: ScrapperConfig,
) {

    /**
     * 落一次成功抓取。
     *
     * 资产按**归属分别处理**，两层的写入强度刻意不同：
     *
     * | 归属 | 写入语义 | 为什么 |
     * |---|---|---|
     * | PAGE（社交图/截图） | 整体替换 | 就是这个页面的内容，页面改版后旧图多半已 404 |
     * | SITE（favicon/logo），抓的是首页 | 整体替换 | 首页是站点图标的权威来源 |
     * | SITE，抓的是深链 | **只补齐缺失的，绝不删** | 见下 |
     *
     * 最后一条是这个方法里唯一不显然的地方。站点图标现在是同域所有页面共享的一份，如果任何
     * 一个深链抓取都能整体替换它，那么同一站点两个页面的抓取会互相覆盖 —— 而
     * `ParseLock` 是**按 bookmark** 加的，根本挡不住这种跨页面的竞争（两把锁，两个不同的 id）。
     * 更糟的是深链页面声明的图标常常还不如首页全（很多站只在首页放 manifest）。
     *
     * 把「整体替换」这唯一的破坏性操作收窄到首页那一条记录上，竞争就重新落回单条 bookmark
     * 的锁里了，不需要再引入一把站点级锁。
     *
     * @param isRootPage 这次抓的是不是站点首页（`bookmark.isRootPage`）
     */
    @Transactional(rollbackFor = [Exception::class])
    fun persist(
        siteId: String,
        bookmarkId: String,
        url: String,
        response: ScrapeResponse,
        durationMs: Int,
        isRootPage: Boolean,
    ) {
        val p = SiteAssetIngestor.project(siteId, bookmarkId, url, response, durationMs, objectMapper)

        scrapeSnapshotMapper.insert(p.snapshot)

        p.pageMeta?.let { meta ->
            val exists = sitePageMetaMapper.selectById(bookmarkId) != null
            if (exists) sitePageMetaMapper.updateById(meta) else sitePageMetaMapper.insert(meta)
        }

        val (siteAssets, pageAssets) = p.assets.partition { it.ownerType == AssetOwnerType.SITE }

        replaceAssets(AssetOwnerType.PAGE, bookmarkId, pageAssets)
        if (isRootPage) {
            replaceAssets(AssetOwnerType.SITE, siteId, siteAssets)
        } else {
            fillMissingAssets(siteId, siteAssets)
        }

        log.debug(
            "[SiteAssetWriter] 落库完成: bookmarkId={}, siteId={}, isRootPage={}, siteAssets={}, pageAssets={}, hasMeta={}",
            bookmarkId, siteId, isRootPage, siteAssets.size, pageAssets.size, p.pageMeta != null
        )
    }

    /**
     * 整体替换某个归属下的资产：先删旧的再写新的。
     *
     * 增量合并没有意义 —— 页面改版后旧图标可能已经 404，留着只会让选取策略挑到死链。
     */
    private fun replaceAssets(ownerType: AssetOwnerType, ownerId: String, projected: List<SiteAssetEntity>) {
        if (ownerId.isBlank()) return
        val existing = assetsOf(ownerType, ownerId)

        // 内容定期重抓开启后，绝大多数重抓的结果与库里**完全一样**（站点没改版）。
        // 照旧走一遍"删全部 + 插全部"只是白写一堆行、白调一次孤儿回收，还把 id 和
        // fetched_at 全换一遍，让"这张图什么时候第一次见到"这个信息凭空丢失。
        if (isIdenticalToExisting(existing, projected)) {
            log.debug(
                "[SiteAssetWriter] 资产与库中完全一致，跳过整体替换: ownerType={}, ownerId={}, assets={}",
                ownerType, ownerId, projected.size
            )
            return
        }
        // 本次没抓到任何这一层的资产时也不要清空：抓取偶发少返回一次不该让站点图标凭空消失，
        // 留着上一次的结果是更好的降级。真正的失效由「整体替换」在有新数据时完成。
        if (projected.isEmpty()) {
            log.debug(
                "[SiteAssetWriter] 本次未抓到该层资产，保留库中现值: ownerType={}, ownerId={}, existing={}",
                ownerType, ownerId, existing.size
            )
            return
        }

        // 整体替换会让上一轮的 OSS 对象失去最后一个引用者，得记下来事后回收
        val previousKeys = existing.mapNotNull { it.storageUrl?.trim()?.takeIf(String::isNotEmpty) }.toSet()

        siteAssetMapper.delete(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, ownerType)
                .eq(SiteAssetEntity::ownerId, ownerId)
        )
        projected.forEach { siteAssetMapper.insert(it) }

        val currentKeys = projected.mapNotNull { it.storageUrl?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        scheduleOrphanCleanup(ownerId, previousKeys - currentKeys)
    }

    /**
     * 深链抓取对站点图标的贡献：**只插入库里还没有的那些，一条都不删**。
     *
     * 站点已经有图标了就整批跳过 —— 深链页面声明的图标通常不如首页全（manifest 往往只挂在
     * 首页），拿它去补充一个已经完整的图标集只会引入一堆降级候选。只有在站点侧一张图都没有
     * 时才有补齐的价值，比如用户添加的第一条书签就是深链、首页从未被抓过。
     *
     * 「站点侧一张图都没有」这个前置检查与插入之间存在一个窄窗口：若另一次抓取正好在这中间提交，
     * 插入会撞上 `idx_site_asset_unique`。**这里刻意不捕获那个异常** —— 在 PostgreSQL 里事务内
     * 一旦触发约束冲突，整个事务就进入 aborted 状态，`runCatching` 之后的语句照样全部失败，
     * 捕获只会把"事务已经废了"伪装成"已跳过"。让它照常回滚：本次抓取的写入丢掉，而对账任务
     * 会重新抓一次，届时站点图标已由对手补齐，走的就是上面那条 early return。
     */
    private fun fillMissingAssets(siteId: String, projected: List<SiteAssetEntity>) {
        if (siteId.isBlank() || projected.isEmpty()) return
        if (assetsOf(AssetOwnerType.SITE, siteId).isNotEmpty()) return

        projected.forEach { siteAssetMapper.insert(it) }
        log.debug("[SiteAssetWriter] 深链抓取补齐了站点图标: siteId={}, assets={}", siteId, projected.size)
    }

    /**
     * 本次投影出的资产是否与库中现存的完全等价。
     *
     * 比较的是**全部由抓取与策略推导出来的列**，不只是 `content_hash`。只比哈希会破坏
     * 「改 [AssetRolePolicy] 的规则后重新抓一遍即可生效、无需改 scrapper」这条性质：
     * 规则改了而图片没变时，哈希一样但 role/quality/isPrimary 已经不同，必须照常重写。
     *
     * 不参与比较的只有 `id`（每次投影都新生成）和 `fetchedAt`（本次抓取时间，本就该保留旧值）。
     */
    private fun isIdenticalToExisting(existing: List<SiteAssetEntity>, projected: List<SiteAssetEntity>): Boolean {
        if (existing.size != projected.size || existing.isEmpty()) return false
        fun SiteAssetEntity.identity() = listOf(
            role, extractor, quality, originUrl, resolvedUrl, storageUrl,
            width, height, byteSize, mime, isVector, contentHash, isPrimary, errorMsg,
        )
        return existing.map { it.identity() }.sortedBy { it.toString() } ==
            projected.map { it.identity() }.sortedBy { it.toString() }
    }

    /** 某个归属下当前的全部资产行。 */
    private fun assetsOf(ownerType: AssetOwnerType, ownerId: String): List<SiteAssetEntity> =
        siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, ownerType)
                .eq(SiteAssetEntity::ownerId, ownerId)
        )

    /**
     * 回收本次重抓后彻底没人引用的 OSS 对象。
     *
     * 以前只删行不删对象，站点每改一次版就在桶里留下一份永远不会被读到的旧图，且没有任何
     * 清理入口。
     *
     * 三条安全边界，缺一不可：
     * 1. **跨书签引用计数**。scrapper 的 object key 是源图 URL 的 SHA-256，因此共用同一张
     *    favicon 的多个书签会指向**同一个 key**，只看本书签就删会删掉别人还在用的图。
     * 2. **只删裸 key**。`storage_url` 里还有改造前写入的完整 URL，可能指向外部域名，一律不碰。
     * 3. **提交后才删**。事务回滚时行还在、对象却没了才是最糟的结果，所以挂在 afterCommit 上。
     *
     * 即便判断失误代价也有限：key 由源 URL 决定，下一次重抓会用同样的 key 把对象重新传上去。
     */
    private fun scheduleOrphanCleanup(ownerId: String, candidates: Set<String>) {
        if (!scrapperConfig.reclaimOrphanAssets) return
        val keys = candidates.filterNot { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (keys.isEmpty()) return

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有事务上下文（理论上不该发生，persist 是 @Transactional 的）时保守跳过，
            // 宁可留下孤儿对象也不要在可能回滚的路径上删东西
            log.warn("[SiteAssetWriter] 无事务上下文，跳过 OSS 孤儿对象回收: ownerId={}, keys={}", ownerId, keys.size)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = deleteUnreferenced(ownerId, keys)
        })
    }

    /** 引用计数归零的 key 才真正删；删除失败只记日志（[OssUtils.delete] 本身已吞异常）。 */
    private fun deleteUnreferenced(ownerId: String, keys: List<String>) {
        runCatching {
            val stillReferenced = siteAssetMapper.selectList(
                KtQueryWrapper(SiteAssetEntity::class.java)
                    .select(SiteAssetEntity::storageUrl)
                    .`in`(SiteAssetEntity::storageUrl, keys)
            ).mapNotNull { it.storageUrl }.toSet()

            val orphans = keys - stillReferenced
            orphans.forEach { OssUtils.delete(it) }
            if (orphans.isNotEmpty()) log.info(
                "[SiteAssetWriter] 已回收无引用的 OSS 对象: ownerId={}, deleted={}, keptShared={}",
                ownerId, orphans.size, keys.size - orphans.size
            )
        }.onFailure {
            // 回收失败只是留下孤儿对象，不该反过来影响已经提交的抓取结果
            log.warn("[SiteAssetWriter] OSS 孤儿对象回收失败(忽略): ownerId={}, err={}", ownerId, it.message)
        }
    }

    /** 落一次失败抓取：只留快照，便于事后排查。资产与元数据保持上一次的值不动。 */
    @Transactional(rollbackFor = [Exception::class])
    fun persistFailure(bookmarkId: String, url: String, errorMsg: String?, durationMs: Int) {
        val p = SiteAssetIngestor.projectFailure(bookmarkId, url, errorMsg, durationMs)
        scrapeSnapshotMapper.insert(p.snapshot)
    }

    /** 供管理后台单独改某个字段用（不走抓取流程）。 */
    fun upsertPageMeta(meta: SitePageMetaEntity) {
        val exists = sitePageMetaMapper.selectById(meta.bookmarkId) != null
        if (exists) sitePageMetaMapper.updateById(meta) else sitePageMetaMapper.insert(meta)
    }

    /** 读取某书签的文字元数据，不存在时给一个未持久化的空实例。 */
    fun pageMetaOf(bookmarkId: String): SitePageMetaEntity =
        sitePageMetaMapper.selectById(bookmarkId) ?: SitePageMetaEntity(bookmarkId = bookmarkId)
}
