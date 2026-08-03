package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
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
interface ScrapeSnapshotMapper : BaseMapper<ScrapeSnapshotEntity> {

    /**
     * 每个书签只保留最近 [keep] 份快照，更早的删掉。返回删除行数。
     *
     * **刻意不做按时间过期。** 这张表的用途是「将来想启用某个当时没提列的字段时，
     * 从历史快照回填而不必重爬」——那就要求每个书签**永远**至少留着一份。按 90 天过期
     * 会把恰好三个月没重抓过的书签的唯一一份快照删掉，正好销毁这张表存在的理由。
     * 按份数保留则与抓取频率无关：抓得多的多删，抓得少的一份不动。
     *
     * 排序里追加 `id DESC` 做次级键：同一书签同一秒内可能落多份（重试），
     * 只按 fetched_at 排会得到不稳定的名次，每次执行删的行都不一样。
     */
    @Delete(
        """
        DELETE FROM scrape_snapshot s USING (
            SELECT id, row_number() OVER (
                       PARTITION BY bookmark_id ORDER BY fetched_at DESC, id DESC
                   ) AS rn
            FROM scrape_snapshot
        ) ranked
        WHERE s.id = ranked.id AND ranked.rn > #{keep}
        """
    )
    fun purgeKeepingLatestPerBookmark(@Param("keep") keep: Int): Int
}
