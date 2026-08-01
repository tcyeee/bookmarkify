package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
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

    /* 归属。分界线沿用 role，不新造概念：FAVICON/LOGO 天生是站点级（全站一套），
     * SOCIAL/SCREENSHOT 天生是页面级（就是页面内容本身）。见 AssetOwnerType。 */
    @field:Schema(description = "归属层级") var ownerType: AssetOwnerType = AssetOwnerType.PAGE,
    @field:Schema(description = "归属ID：SITE 时是 site.id，PAGE 时是 bookmark.id") var ownerId: String = "",

    /**
     * 最初由哪个页面的抓取带回来的，**仅供溯源**。归属一律看 [ownerType] / [ownerId]。
     *
     * 站点图标是所有页面共享的一行，"属于哪个 bookmark" 这个问题对它没有意义 ——
     * 留着这一列只是为了排查"这张图当初是从哪个页面抓到的"。
     */
    @field:Schema(description = "最初由哪个页面抓来的(溯源用，勿用于归属判断)") var bookmarkId: String? = null,

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

    /**
     * 指向 `oss_object` 的引用，**读路径的首选来源**。
     *
     * 存 id 而不是 key，是为了把这张表与存储层的 key 布局隔开：将来 key 怎么变（去扩展名、
     * 改内容寻址、换前缀），只需重写 `oss_object.object_key` 一列，这里一行都不用动。
     *
     * 可空，且**永远会有可空的理由**：只做了 PROBE 的资产、指向外站直连的资产根本没落对象
     * 存储；再加上改造前写入的完整 URL 存量。见 `FILE-SYSTEM-REFACTOR.md` §6 约束 B。
     *
     * 不参与 `SiteAssetWriter.isIdenticalToExisting` 的比较：入账按 key 幂等，同一个 key
     * 永远拿到同一个 id，所以它是 [storageUrl] 的函数，比了也提供不了新信息 —— 反而在账本
     * 暂时不可用时（id 为空而 key 有值）会让每次重抓都误判成"变了"。
     */
    @field:Schema(description = "对象账本ID(oss_object.id)") var fileId: String? = null,

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
 * 展示偏好：按（**站点** × [DisplayMode]）分行。
 *
 * **只由人工写入。** 重抓流程写 [SitePageMetaEntity] 与 [SiteAssetEntity]，永不触碰这张表 ——
 * 旧的 `bookmark_logo` 把抓取事实与人工偏好混在一起，导致每次重抓都得做小心翼翼的部分更新。
 *
 * 键从「书签」改成「站点」：内边距、背景色、钉死哪张图，调的都是**站点图标**的观感，
 * 同域名下每个页面各存一份等于让管理员把同一件事做 N 遍。
 */
@TableName("site_display_pref")
data class SiteDisplayPrefEntity(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "所属站点ID") var siteId: String = "",
    /** 历史列：最初在哪个页面上调的，仅供溯源，业务代码不读。 */
    @field:Schema(description = "最初在哪个页面上调的(溯源用)") var bookmarkId: String? = null,
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
