package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import java.time.LocalDateTime

/**
 * 网站图片资产：**一行一图**。
 *
 * 取代旧的 `bookmark_logo.iconBase64` / `logoUrl` 两个扁平图位。扁平列扛不住两件事：
 * 图片种类会增长（截图、暗色 LOGO、maskable icon…），以及同一站点会声明多张同类图。
 * 改成一行一图后，新增种类无需改表，选哪张也变成一次查询而非一个硬编码分支。
 *
 * [extractor] 是 scrapper 报告的**事实**，[role] / [quality] 是本服务推导的**判断**，
 * 二者刻意分列存放，见 [top.tcyeee.bookmarkify.server.asset.AssetRolePolicy]。
 */
@TableName("site_asset")
data class SiteAssetEntity(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "所属书签ID") var bookmarkId: String = "",

    @field:Schema(description = "用途(本服务推导)") var role: AssetRole = AssetRole.FAVICON,
    @field:Schema(description = "出处(scrapper 报告的事实)") var extractor: String = "",
    @field:Schema(description = "可信度分级") var quality: AssetQuality = AssetQuality.DEGRADED,

    @field:Schema(description = "声明中的原始地址,可能是相对路径") var originUrl: String = "",
    @field:Schema(description = "解析后的绝对地址") var resolvedUrl: String = "",
    /**
     * 落对象存储后的引用。**两种形态并存**：新写入的是 scrapper 返回的 object key（不含域名），
     * 存量数据是改造前写入的完整 URL。统一交给 `OssUtils.signAsset` 分流，无需数据迁移。
     * 列名保持 `storage_url` 不变，避免动 DDL 与后台接口字段名。
     */
    @field:Schema(description = "落对象存储后的 object key（存量数据可能是完整 URL）")
    var storageUrl: String? = null,

    @field:Schema(description = "真实像素宽") var width: Int? = null,
    @field:Schema(description = "真实像素高") var height: Int? = null,
    @field:Schema(description = "字节数") var byteSize: Long? = null,
    @field:Schema(description = "实际 MIME") var mime: String? = null,
    @field:Schema(description = "是否矢量图(SVG)") var isVector: Boolean = false,

    /**
     * 图片字节的 sha256。
     *
     * 这一列是"该站到底有没有独立 LOGO"的唯一判据：若某张 LOGO 与该站 FAVICON 的
     * hash 相同，说明它只是 favicon 换了个 rel 名字，[quality] 应视为降级。
     */
    @field:Schema(description = "图片字节 sha256") var contentHash: String? = null,

    @field:Schema(description = "同 role 内的自动首选项") var isPrimary: Boolean = false,
    @field:Schema(description = "该张的处理失败原因") var errorMsg: String? = null,
    @field:Schema(description = "抓取时间") var fetchedAt: LocalDateTime = LocalDateTime.now(),
) {
    /** 有效渲染尺寸：矢量图无固有像素，视为足够大 */
    fun effectiveSize(): Int = when {
        isVector -> Int.MAX_VALUE
        else -> minOf(width ?: 0, height ?: 0)
    }

    /** 能否真正渲染：要么落了对象存储，要么有可直连的原始地址，且没出错 */
    fun renderable(): Boolean = errorMsg == null && (storageUrl != null || resolvedUrl.isNotBlank())
}

/**
 * 页面文字元数据。与 [SiteAssetEntity] 一样，只由抓取流程写入。
 */
@TableName("site_page_meta")
data class SitePageMetaEntity(
    @TableId var bookmarkId: String = "",
    @field:Schema(description = "页面标题") var title: String? = null,
    @field:Schema(description = "页面描述") var description: String? = null,
    @field:Schema(description = "站点名(列表模式用)") var siteName: String? = null,
    @field:Schema(description = "站点短名(大图模式用,仅来自 manifest.short_name)") var siteShortName: String? = null,
    @field:Schema(description = "canonical 地址") var canonicalUrl: String? = null,
    @field:Schema(description = "页面语言") var lang: String? = null,
    @field:Schema(description = "主题色") var themeColor: String? = null,
    @field:Schema(description = "各字段出处(JSON)") var metaSources: String? = null,
    @field:Schema(description = "实际抓取层 HTTP/HEADLESS") var fetchLayer: String? = null,
    @field:Schema(description = "HTTP状态码") var httpStatus: Int? = null,
    @field:Schema(description = "疑似反爬挑战页,内容不可靠") var antiCrawler: Boolean = false,
    @field:Schema(description = "抓取时间") var fetchedAt: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "更新时间") var updateTime: LocalDateTime = LocalDateTime.now(),
)

/**
 * 展示偏好：按（书签 × [DisplayMode]）分行。
 *
 * **只由人工写入。** 重抓流程写 [SitePageMetaEntity] 与 [SiteAssetEntity]，永不触碰这张表 ——
 * 旧的 `bookmark_logo` 把抓取事实与人工偏好混在一起，导致每次重抓都得做小心翼翼的部分更新。
 */
@TableName("site_display_pref")
data class SiteDisplayPrefEntity(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "所属书签ID") var bookmarkId: String = "",
    @field:Schema(description = "展示模式") var displayMode: DisplayMode = DisplayMode.TILE,
    @field:Schema(description = "图片内边距") var iconPadding: Int = 25,
    @field:Schema(description = "图标背景色") var iconBgColor: String? = null,
    @field:Schema(description = "人工钉死的资产ID,覆盖自动选择") var pinnedAssetId: String? = null,
    @field:Schema(description = "操作人") var updatedBy: String? = null,
    @field:Schema(description = "更新时间") var updateTime: LocalDateTime = LocalDateTime.now(),
)

/**
 * scrapper 响应的原样留档。
 *
 * 结构化表都是从 [response] 投影出来的。留快照是为了将来能**回填**而不必重爬：想启用某个
 * 当时没提列的字段（例如拿 themeColor 做卡片背景色）时，直接从历史快照里取即可。
 */
@TableName("scrape_snapshot")
data class ScrapeSnapshotEntity(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "所属书签ID") var bookmarkId: String = "",
    @field:Schema(description = "请求URL") var url: String = "",
    @field:Schema(description = "是否成功") var ok: Boolean = false,
    @field:Schema(description = "实际生效的请求参数(JSON)") var request: String? = null,
    @field:Schema(description = "scrapper 响应全文(JSON)") var response: String? = null,
    @field:Schema(description = "错误信息") var errorMsg: String? = null,
    @field:Schema(description = "耗时(ms)") var durationMs: Int = 0,
    @field:Schema(description = "抓取时间") var fetchedAt: LocalDateTime = LocalDateTime.now(),
)
