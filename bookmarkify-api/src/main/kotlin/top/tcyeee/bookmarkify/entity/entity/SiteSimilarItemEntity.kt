package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 「更多相似书签」缓存的一行 —— 一个由 DeepSeek 推荐、并尝试收录过的相似站点。
 *
 * **站点级**：`site_id` 是属主（换个用户 / 换同域另一页，推荐结果都不变）。冷计算按 site
 * 触发一次、整组写入，TTL 由 `create_time` 控制（见 `SimilarBookmarkServiceImpl`）。
 *
 * 本地库那一路（共享 category 的站点）不落这张表 —— 它每次现查、零外部成本，也没有 TTL 概念。
 */
@TableName("site_similar_item")
data class SiteSimilarItemEntity(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "属主站点(site.id)") var siteId: String,
    @field:Schema(description = "DeepSeek 返回顺序，越小越靠前") var rank: Int = 0,
    @field:Schema(description = "站点名称") var name: String,
    @field:Schema(description = "主域名，不带协议前缀") var domain: String,
    @field:Schema(description = "一句话相似理由") var reason: String? = null,
    @field:Schema(description = "PENDING=后台抓取中；INGESTED=已入库；SKIPPED=抓不到正文(幻觉/失效)")
    var status: String = STATUS_PENDING,
    @JsonIgnore @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "最近更新时间") var updateTime: LocalDateTime? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_INGESTED = "INGESTED"
        const val STATUS_SKIPPED = "SKIPPED"
    }
}
