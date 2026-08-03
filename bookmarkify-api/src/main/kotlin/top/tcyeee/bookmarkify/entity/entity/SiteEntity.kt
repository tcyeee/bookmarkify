package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.SiteLockedField
import top.tcyeee.bookmarkify.utils.LockedFieldCodec
import top.tcyeee.bookmarkify.utils.WebsiteParser
import java.time.LocalDateTime

/**
 * 站点：**一个域名一行**，承载所有「换个页面也不会变」的事实。
 *
 * 为什么要有这一层（详见根目录 `SITE_LAYERING_DESIGN.md`）：`bookmark` 原先一张表同时扮演
 * 「站点」和「页面」。同域名下 1000 个页面就意味着 1000 份 favicon 抓取+OSS 上传、
 * 1000 次 DeepSeek NSFW 判定、1000 次域名 ping、管理员调 1000 次图标内边距 —— 而这些值
 * 换个页面根本不会变。分层之后这些全部是每域名一次。
 *
 * 归属判断只用一个问题：**换一个用户 / 换同域下另一个页面，这个值会变吗？** 都不变的进这里，
 * 只随页面变的留在 [PageEntity]，随用户变的留在 [BookmarkEntity]。
 */
@TableName("site")
data class SiteEntity(
    @TableId var id: String = IdUtil.fastUUID(),

    /* 身份 */
    @field:Size(max = 200) @field:Schema(description = "域名(含端口)") var host: String,
    @field:Size(max = 10) @field:Schema(description = "基础HTTP协议") var scheme: String,
    @field:Schema(description = "链接类型(域名/本地/IP/其他)") var linkType: BookmarkLinkType = BookmarkLinkType.OTHER,

    /* 品牌信息。权威值只由**首页抓取**写入：深链页面也会返回 og:site_name，但那是二等来源，
     * 只在本列为空时回填 —— 否则某个视频页里写歪的 site_name 会把整站品牌名带跑。 */
    @field:Size(max = 200) @field:Schema(description = "站点全名(og:site_name / manifest.name)") var brandName: String? = null,
    @field:Size(max = 100) @field:Schema(description = "站点短名(manifest.short_name)，磁贴文案用") var shortName: String? = null,

    /* 内容判定。站点级属性：同一个域名不必逐页判一遍 */
    @field:Schema(description = "疑似涉黄/涉赌等违规内容(NSFW)，由 DeepSeek 判断") var nsfw: Boolean = false,
    @JsonIgnore @field:Size(max = 50) @field:Schema(description = "NSFW 判定理由") var nsfwReason: String? = null,

    /* 域名级活性与巡检调度。与 PageEntity.pageAlive 刻意分开：域名活着而具体页面 404
     * 是常态（视频被删、仓库归档），反过来域名死了就没必要逐页去探测。 */
    @JsonIgnore @field:Schema(description = "域名是否可达") var isAlive: Boolean = true,
    @JsonIgnore @field:Schema(description = "上次域名探测时间(不论结论)") var lastCheckAt: LocalDateTime? = null,
    @JsonIgnore @field:Schema(description = "下次域名巡检时间(调度游标)") var nextCheckAt: LocalDateTime? = null,
    @JsonIgnore @field:Schema(description = "连续探测失败次数，驱动指数退避") var consecutiveFail: Int = 0,

    /* 人工干预 */
    @JsonIgnore @field:Schema(description = "人工认证：品牌名与图标已核对，抓取不再覆盖") var verifyFlag: Boolean = false,
    @JsonIgnore @field:Size(max = 200) @field:Schema(description = "管理员手工锁定的字段(逗号分隔)") var lockedFields: String? = null,

    @JsonIgnore @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "最近更新时间") var updateTime: LocalDateTime? = null,
) {
    /** 站点首页地址。域名级活性探测打的是这里，而不是某个具体页面。 */
    val rootUrl: String get() = "$scheme://$host/"

    /** 展示用的站点名：短名 → 全名 → 域名。真正的展示优先级见 BookmarkDisplayPolicy，这里只是兜底。 */
    val displayName: String
        get() = shortName?.takeIf { it.isNotBlank() }
            ?: brandName?.takeIf { it.isNotBlank() }
            ?: host

    constructor(host: String, scheme: String) : this(
        id = IdUtil.fastUUID(),
        host = host,
        scheme = scheme,
        linkType = WebsiteParser.classifyLinkType(host),
    )

    val lockedFieldSet: Set<SiteLockedField> get() = LockedFieldCodec.decode(lockedFields)

    fun isLocked(field: SiteLockedField): Boolean = field in lockedFieldSet

    /** 加锁（管理员手工编辑了该字段）。 */
    fun lock(vararg fields: SiteLockedField) = setLockedFields(lockedFieldSet + fields)

    /** 解锁（管理员显式接受了抓取来的值，该字段此后不再是人工值）。 */
    fun unlock(vararg fields: SiteLockedField) = setLockedFields(lockedFieldSet - fields.toSet())

    private fun setLockedFields(fields: Set<SiteLockedField>) {
        lockedFields = LockedFieldCodec.encode(fields)
    }
}
