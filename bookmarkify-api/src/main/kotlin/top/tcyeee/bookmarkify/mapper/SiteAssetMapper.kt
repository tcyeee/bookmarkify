package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import top.tcyeee.bookmarkify.entity.entity.ScrapeSnapshotEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.SiteDisplayPrefEntity
import top.tcyeee.bookmarkify.entity.entity.SitePageMetaEntity

/** 网站图片资产（一行一图） */
@Mapper
interface SiteAssetMapper : BaseMapper<SiteAssetEntity>

/** 页面文字元数据 */
@Mapper
interface SitePageMetaMapper : BaseMapper<SitePageMetaEntity>

/** 展示偏好（书签 × 展示模式），只由人工写入 */
@Mapper
interface SiteDisplayPrefMapper : BaseMapper<SiteDisplayPrefEntity>

/** scrapper 响应快照 */
@Mapper
interface ScrapeSnapshotMapper : BaseMapper<ScrapeSnapshotEntity>
