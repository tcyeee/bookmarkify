package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.SiteDisplayPrefEntity
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.mapper.SiteDisplayPrefMapper
import java.time.LocalDateTime

/**
 * 展示偏好的读写，按（书签 × [DisplayMode]）分行。
 *
 * 独立于抓取流程：这里存的是**人工调好的**内边距/背景色/钉死的图，重抓永远不会覆盖它们。
 */
@Service
class SiteDisplayPrefService(private val mapper: SiteDisplayPrefMapper) {

    /** upsert 某个模式下的显示设置。 */
    fun save(
        bookmarkId: String,
        mode: DisplayMode,
        iconPadding: Int,
        iconBgColor: String?,
        pinnedAssetId: String?,
        updatedBy: String? = null,
    ) {
        val existing = find(bookmarkId, mode)
        val entity = (existing ?: SiteDisplayPrefEntity(bookmarkId = bookmarkId, displayMode = mode)).apply {
            this.iconPadding = iconPadding
            this.iconBgColor = iconBgColor
            this.pinnedAssetId = pinnedAssetId
            this.updatedBy = updatedBy
            this.updateTime = LocalDateTime.now()
        }
        if (existing == null) mapper.insert(entity) else {
            mapper.update(
                entity,
                KtUpdateWrapper(SiteDisplayPrefEntity::class.java)
                    .eq(SiteDisplayPrefEntity::bookmarkId, bookmarkId)
                    .eq(SiteDisplayPrefEntity::displayMode, mode)
            )
        }
    }

    /** 取某模式下的设置；没有则返回 null（调用方自行决定默认值）。 */
    fun find(bookmarkId: String, mode: DisplayMode): SiteDisplayPrefEntity? = mapper.selectList(
        KtQueryWrapper(SiteDisplayPrefEntity::class.java)
            .eq(SiteDisplayPrefEntity::bookmarkId, bookmarkId)
            .eq(SiteDisplayPrefEntity::displayMode, mode)
    ).firstOrNull()

    /** 取该书签所有模式的设置。 */
    fun findAll(bookmarkId: String): List<SiteDisplayPrefEntity> = mapper.selectList(
        KtQueryWrapper(SiteDisplayPrefEntity::class.java).eq(SiteDisplayPrefEntity::bookmarkId, bookmarkId)
    )
}
