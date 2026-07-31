package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.SiteDisplayPrefEntity
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.mapper.SiteDisplayPrefMapper
import java.time.LocalDateTime

/**
 * 展示偏好的读写，按（**站点** × [DisplayMode]）分行。
 *
 * 独立于抓取流程：这里存的是**人工调好的**内边距/背景色/钉死的图，重抓永远不会覆盖它们。
 *
 * 键是站点而不是书签：内边距、背景色、钉死哪张图，调的都是**站点图标**的观感。
 * 按书签存等于让管理员在同一个域名下把同一件事做 N 遍
 * （详见根目录 `SITE_LAYERING_DESIGN.md` §5）。
 */
@Service
class SiteDisplayPrefService(private val mapper: SiteDisplayPrefMapper) {

    /**
     * upsert 某个站点在某模式下的显示设置。
     *
     * @param bookmarkId 仅作溯源留档（"最初是在哪个页面上调的"），不参与键
     */
    fun save(
        siteId: String,
        mode: DisplayMode,
        iconPadding: Int,
        iconBgColor: String?,
        pinnedAssetId: String?,
        updatedBy: String? = null,
        bookmarkId: String? = null,
    ) {
        val existing = find(siteId, mode)
        val entity = (existing ?: SiteDisplayPrefEntity(siteId = siteId, displayMode = mode)).apply {
            this.iconPadding = iconPadding
            this.iconBgColor = iconBgColor
            this.pinnedAssetId = pinnedAssetId
            this.updatedBy = updatedBy
            this.updateTime = LocalDateTime.now()
            bookmarkId?.let { this.bookmarkId = it }
        }
        if (existing == null) mapper.insert(entity) else {
            mapper.update(
                entity,
                KtUpdateWrapper(SiteDisplayPrefEntity::class.java)
                    .eq(SiteDisplayPrefEntity::siteId, siteId)
                    .eq(SiteDisplayPrefEntity::displayMode, mode)
            )
        }
    }

    /** 取某模式下的设置；没有则返回 null（调用方自行决定默认值）。 */
    fun find(siteId: String, mode: DisplayMode): SiteDisplayPrefEntity? = mapper.selectList(
        KtQueryWrapper(SiteDisplayPrefEntity::class.java)
            .eq(SiteDisplayPrefEntity::siteId, siteId)
            .eq(SiteDisplayPrefEntity::displayMode, mode)
    ).firstOrNull()

    /** 取该站点所有模式的设置。 */
    fun findAll(siteId: String): List<SiteDisplayPrefEntity> = mapper.selectList(
        KtQueryWrapper(SiteDisplayPrefEntity::class.java).eq(SiteDisplayPrefEntity::siteId, siteId)
    )
}
