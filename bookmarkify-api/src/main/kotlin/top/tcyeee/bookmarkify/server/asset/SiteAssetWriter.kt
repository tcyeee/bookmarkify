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
     * 资产采用**整体替换**语义：先删该书签的旧资产再写新的。增量合并没有意义 ——
     * 页面改版后旧图标可能已经 404，留着只会让选取策略挑到死链。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun persist(bookmarkId: String, url: String, response: ScrapeResponse, durationMs: Int) {
        val p = SiteAssetIngestor.project(bookmarkId, url, response, durationMs, objectMapper)

        scrapeSnapshotMapper.insert(p.snapshot)

        p.pageMeta?.let { meta ->
            val exists = sitePageMetaMapper.selectById(bookmarkId) != null
            if (exists) sitePageMetaMapper.updateById(meta) else sitePageMetaMapper.insert(meta)
        }

        val existing = assetsOf(bookmarkId)
        // 内容定期重抓开启后，绝大多数重抓的结果与库里**完全一样**（站点没改版）。
        // 照旧走一遍"删全部 + 插全部"只是白写一堆行、白调一次孤儿回收，还把 id 和
        // fetched_at 全换一遍，让"这张图什么时候第一次见到"这个信息凭空丢失。
        if (isIdenticalToExisting(existing, p.assets)) {
            log.debug(
                "[SiteAssetWriter] 资产与库中完全一致，跳过整体替换: bookmarkId={}, assets={}",
                bookmarkId, p.assets.size
            )
            return
        }

        // 整体替换会让上一轮的 OSS 对象失去最后一个引用者，得记下来事后回收
        val previousKeys = existing.mapNotNull { it.storageUrl?.trim()?.takeIf(String::isNotEmpty) }.toSet()

        siteAssetMapper.delete(
            KtQueryWrapper(SiteAssetEntity::class.java).eq(SiteAssetEntity::bookmarkId, bookmarkId)
        )
        p.assets.forEach { siteAssetMapper.insert(it) }

        val currentKeys = p.assets.mapNotNull { it.storageUrl?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        scheduleOrphanCleanup(bookmarkId, previousKeys - currentKeys)

        log.debug(
            "[SiteAssetWriter] 落库完成: bookmarkId={}, assets={}, hasMeta={}",
            bookmarkId, p.assets.size, p.pageMeta != null
        )
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

    /** 某书签当前的全部资产行。 */
    private fun assetsOf(bookmarkId: String): List<SiteAssetEntity> = siteAssetMapper.selectList(
        KtQueryWrapper(SiteAssetEntity::class.java).eq(SiteAssetEntity::bookmarkId, bookmarkId)
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
    private fun scheduleOrphanCleanup(bookmarkId: String, candidates: Set<String>) {
        if (!scrapperConfig.reclaimOrphanAssets) return
        val keys = candidates.filterNot { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (keys.isEmpty()) return

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有事务上下文（理论上不该发生，persist 是 @Transactional 的）时保守跳过，
            // 宁可留下孤儿对象也不要在可能回滚的路径上删东西
            log.warn("[SiteAssetWriter] 无事务上下文，跳过 OSS 孤儿对象回收: bookmarkId={}, keys={}", bookmarkId, keys.size)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = deleteUnreferenced(bookmarkId, keys)
        })
    }

    /** 引用计数归零的 key 才真正删；删除失败只记日志（[OssUtils.delete] 本身已吞异常）。 */
    private fun deleteUnreferenced(bookmarkId: String, keys: List<String>) {
        runCatching {
            val stillReferenced = siteAssetMapper.selectList(
                KtQueryWrapper(SiteAssetEntity::class.java)
                    .select(SiteAssetEntity::storageUrl)
                    .`in`(SiteAssetEntity::storageUrl, keys)
            ).mapNotNull { it.storageUrl }.toSet()

            val orphans = keys - stillReferenced
            orphans.forEach { OssUtils.delete(it) }
            if (orphans.isNotEmpty()) log.info(
                "[SiteAssetWriter] 已回收无引用的 OSS 对象: bookmarkId={}, deleted={}, keptShared={}",
                bookmarkId, orphans.size, keys.size - orphans.size
            )
        }.onFailure {
            // 回收失败只是留下孤儿对象，不该反过来影响已经提交的抓取结果
            log.warn("[SiteAssetWriter] OSS 孤儿对象回收失败(忽略): bookmarkId={}, err={}", bookmarkId, it.message)
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
