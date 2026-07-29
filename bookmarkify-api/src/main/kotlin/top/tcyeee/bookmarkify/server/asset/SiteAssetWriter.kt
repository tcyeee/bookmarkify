package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.SitePageMetaEntity
import top.tcyeee.bookmarkify.mapper.ScrapeSnapshotMapper
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import top.tcyeee.bookmarkify.mapper.SitePageMetaMapper

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

        siteAssetMapper.delete(
            KtQueryWrapper(SiteAssetEntity::class.java).eq(SiteAssetEntity::bookmarkId, bookmarkId)
        )
        p.assets.forEach { siteAssetMapper.insert(it) }

        log.debug(
            "[SiteAssetWriter] 落库完成: bookmarkId={}, assets={}, hasMeta={}",
            bookmarkId, p.assets.size, p.pageMeta != null
        )
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
