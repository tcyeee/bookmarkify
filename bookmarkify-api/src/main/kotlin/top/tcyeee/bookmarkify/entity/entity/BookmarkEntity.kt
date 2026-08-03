package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.date.LocalDateTimeUtil
import cn.hutool.core.util.IdUtil
import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.dto.BookmarkWrapper
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.BookmarkLockedField
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.utils.ChromeBookmarkRawData
import top.tcyeee.bookmarkify.utils.LockedFieldCodec
import top.tcyeee.bookmarkify.utils.UrlCanonicalizer
import top.tcyeee.bookmarkify.utils.WebsiteParser
import java.io.Serializable
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 书签相关
 *
 * @author tcyeee
 * @date 3/10/24 15:31
 */
@TableName("bookmark")
data class BookmarkEntity(

    /* URL相关 */
    @TableId var id: String,
    // 所属站点。品牌名/短名/favicon/logo/NSFW/域名活性都在 site 那一层，本表只管页面级事实。
    @field:Size(max = 40) @field:Schema(description = "所属站点ID") var siteId: String = "",
    @field:Size(max = 200) @field:Schema(description = "书签根域名(site.host 的冗余副本，只读)") var urlHost: String, // sfz.uzuzuz.com.cn
    // canonical 书签按 (siteId, urlPath, urlQuery, urlFragment) 四元组去重/抓取：
    // 同一域名下不同路径、不同参数（不同 GitHub 仓库、不同 YouTube 视频）是完全不同的页面，
    // 各自应有自己的标题/图标，不能共用同一条记录。规范化规则见 UrlCanonicalizer。
    @field:Size(max = 500) @field:Schema(description = "书签路径，根路径存 \"/\"") var urlPath: String = "/",
    @field:Size(max = 1000) @field:Schema(description = "规范化后的查询参数，无参数存空串") var urlQuery: String = "",
    @field:Size(max = 500) @field:Schema(description = "路由型 fragment(#/… / #!…)，页内锚点不存") var urlFragment: String = "",
    @field:Size(max = 10) @field:Schema(description = "书签基础HTTP协议") var urlScheme: String, // http or https

    /* 基础信息 */
    // appName 是历史遗留：语义上它是**站点短名**（manifest.short_name），已迁往 site.short_name。
    // 保留到清理批次（SITE_LAYERING_DESIGN.md §8 第 6 步）执行前，新代码不要再读写它。
    @field:Size(max = 100) @field:Schema(description = "书签简称(过渡期保留，权威值见 site.short_name)") var appName: String? = null,
    @field:Size(max = 200) @field:Schema(description = "书签标题") var title: String? = null,
    @field:Size(max = 1000) @JsonIgnore @field:Schema(description = "书签备注") var description: String? = null,

    // 图标相关信息已迁往 site_asset / site_display_pref：一行一图 + 按展示模式分行的显示偏好。

    /* 状态信息 */
    @JsonIgnore @field:Schema(description = "是否解析成功") var parseStatus: ParseStatusEnum = ParseStatusEnum.PENDING,
    // 页面级活性：这**一个页面**能否打开。域名级活性在 site.is_alive —— 域名活着而具体页面 404
    // 是常态（视频被删、仓库归档），反过来域名死了就没必要逐页去探测。
    @JsonIgnore @field:Schema(description = "该页面是否可访问(域名级活性见 site.is_alive)") var isActivity: Boolean = false,
    @JsonIgnore @field:Schema(description = "抓取成功但页面疑似反爬虫/WAF挑战页,内容可能不可靠") var antiCrawlerBlocked: Boolean = false,
    @JsonIgnore @field:Schema(description = "手动认证状态") var verifyFlag: Boolean = false, // 如果该书签信息都没问题, 添加手动认证状态以后, 即可被搜索到
    @field:Schema(description = "疑似涉黄/涉赌等违规内容(NSFW)，由 DeepSeek 判断") var nsfw: Boolean = false,
    @JsonIgnore @field:Size(max = 50) @field:Schema(description = "NSFW 判定理由，由 DeepSeek 给出，供人工审核/排查使用") var nsfwReason: String? = null,
    @JsonIgnore @field:Schema(description = "解析失败后的反馈") var parseErrMsg: String? = null,
    @JsonIgnore @field:Schema(description = "添加时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "最近更新时间") var updateTime: LocalDateTime? = null,  // 最近更新时间创建的时候默认为null,表示是刚创建的

    /* 巡检调度状态。刻意与 updateTime 分开：那一列是「记录最近修改时间」，
     * 一旦兼作调度游标，管理员改个标题就会把这条记录的下次巡检推迟一整个检测周期，
     * 而 ping 成功又会反过来污染它的对外语义。定时巡检只看 nextCheckAt 一列。 */
    @JsonIgnore @field:Schema(description = "上次成功抓到内容的时间(内容陈旧度以此为准)") var lastParseAt: LocalDateTime? = null,
    @JsonIgnore @field:Schema(description = "上次活性探测的时间(不论结论)") var lastCheckAt: LocalDateTime? = null,
    @JsonIgnore @field:Schema(description = "下次巡检时间(调度游标)") var nextCheckAt: LocalDateTime? = null,
    @JsonIgnore @field:Schema(description = "连续探测失败次数，驱动指数退避与归档") var consecutiveFail: Int = 0,

    @JsonIgnore
    @field:Size(max = 200)
    @field:Schema(description = "管理员手工锁定、不允许自动抓取覆盖的字段(逗号分隔)")
    var lockedFields: String? = null,
) {
    /**
     * 抓取/ping 的目标：**这一个具体页面**，含规范化后的参数与路由型 fragment。
     *
     * query 曾经不在这里，于是 `youtube.com/watch?v=A` 的抓取目标退化成 `.../watch` ——
     * 一个不存在的页面，抓回来的标题对任何视频都是错的。顺序由 [UrlCanonicalizer.CanonicalParts]
     * 统一负责（`?` 在 `#` 之前），别在这里手拼。
     */
    val rawUrl: String
        get() = UrlCanonicalizer.CanonicalParts(urlPath, urlQuery, urlFragment)
            .rawUrl(urlScheme, urlHost)

    /** 是不是站点首页。展示策略与站点品牌名的写入强度都按它分叉。 */
    val isRootPage: Boolean get() = urlPath == "/" && urlQuery.isEmpty() && urlFragment.isEmpty()

    // JSON格式化后的数据
    val json: String? get() = JSONUtil.toJsonStr(this)

    /**
     * 通过 URL 初始化。[siteId] 必填 —— 页面必须先有站点：品牌名/图标/NSFW/域名活性都挂在
     * site 上，没有 siteId 的页面拿不到任何展示信息，而这种漏挂在编译期看不出来。
     */
    constructor(url: BookmarkUrlWrapper, siteId: String) : this(
        id = IdUtil.fastUUID(),
        siteId = siteId,
        urlHost = url.urlHost,
        urlPath = url.urlPath ?: "/",
        urlQuery = url.urlQuery,
        urlFragment = url.urlFragment,
        urlScheme = url.urlScheme,
        parseStatus = ParseStatusEnum.PENDING,
    )

    fun successInit(wrapper: BookmarkWrapper) {
        this.appName = wrapper.name
        this.isActivity = true
        this.parseErrMsg = null
        this.title = wrapper.title
        this.description = wrapper.description
        this.parseStatus = ParseStatusEnum.SUCCESS
        this.antiCrawlerBlocked = wrapper.antiCrawlerDetected
        this.updateTime = LocalDateTime.now()
        // 图标由 SiteAssetWriter 在保存元信息后单独落 site_asset。
    }

    /** 已锁定的字段集合。无法识别的取值直接忽略，别让一行脏数据把整条书签的解析拖崩。 */
    val lockedFieldSet: Set<BookmarkLockedField> get() = LockedFieldCodec.decode(lockedFields)

    fun isLocked(field: BookmarkLockedField): Boolean = field in lockedFieldSet

    /** 加锁（管理员手工编辑了该字段）。 */
    fun lock(vararg fields: BookmarkLockedField) = setLockedFields(lockedFieldSet + fields)

    /** 解锁（管理员显式接受了抓取来的值，该字段此后不再是人工值）。 */
    fun unlock(vararg fields: BookmarkLockedField) = setLockedFields(lockedFieldSet - fields.toSet())

    private fun setLockedFields(fields: Set<BookmarkLockedField>) {
        // 空集合存 NULL 而不是空字符串，规则与 site.locked_fields 共用，见 LockedFieldCodec
        lockedFields = LockedFieldCodec.encode(fields)
    }

    /**
     * 用户新增这个书签时，已有的解析结果是否已经过期、需要重抓一次。
     *
     * 按小时而不是按天比较：`ChronoUnit.DAYS.between` 会向下取整，配上 `> 1` 之后
     * 「距上次解析 1.9 天」算出来是 1、判定为不需要重抓，实际阈值悄悄变成了满 2 天，
     * 与这里一直写着的「超过 1 天」不符。
     */
    fun checkFlag(): Boolean {
        if (updateTime == null) return true
        return LocalDateTimeUtil.between(updateTime, LocalDateTime.now(), ChronoUnit.HOURS) >= STALE_AFTER_HOURS
    }

    companion object {
        /** 解析结果的有效期：超过这么久，用户再添加同一网址时重抓一次。 */
        private const val STALE_AFTER_HOURS = 24L
    }
}

@TableName("bookmark_user_link")
data class BookmarkUserLink(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Size(max = 40) @field:Schema(description = "用户ID") var uid: String,
    @field:Size(max = 40) @field:Schema(description = "书签ID") val bookmarkId: String?,  // 书签ID可能为null,在用户批量添加的时候,只会添加用户自定义书签,而不会关联到源书签
    @field:Size(max = 40) @field:Schema(description = "用户桌面排布ID") val layoutNodeId: String,

    /**
     * 用户自己写的标题。**`null` 表示用户没改过**，不是"改成了空"。
     *
     * 创建时刻意**不**从 `bookmark.title` 拷一份快照过来。拷过来之后"用户手改的值"和"创建时
     * 的快照"在数据上完全不可区分，于是永远判断不出新一次抓取该不该覆盖它 —— 这也是为什么
     * 页面改版后用户的标题要么永远是旧的、要么被静默冲掉，取决于当时走了哪条代码路径。
     * 留 NULL 之后覆盖策略是显然的：NULL 用页面标题，非 NULL 是用户的、永不覆盖。
     * 见根目录 `SITE_LAYERING_DESIGN.md` §6。
     */
    @field:Size(max = 200) @field:Schema(description = "书签标题(用户写的)；null 表示没改过") val title: String? = null,
    @field:Size(max = 1000) @field:Schema(description = "书签备注(用户写的)") val description: String? = null,
    @field:Size(max = 1000) @field:Schema(description = "书签完整URL(带参数)") val urlFull: String,    // http://sfz.uzuzuz.com.cn/?region=150303%26birthday=19520807%26sex=2%26num=19%26r=82,
    @field:Schema(description = "书签链接类型(域名/本地/IP/其他)") var linkType: BookmarkLinkType = BookmarkLinkType.OTHER,

    @field:Schema(description = "是否置顶") var pinned: Boolean = false,
    @JsonIgnore @field:Schema(description = "用户打开该书签的累计次数(仅做记录)") var openCount: Int = 0,

    @JsonIgnore @field:Schema(description = "创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "是否删除") val deleted: Boolean = false,
) {
    /**
     * 用户添加一个网址时的关联记录。
     *
     * [urlFull] 存的是**用户给的原始网址**（含全部参数），不是规范化后的地址 —— 用户点击永远
     * 走这一列。规范化只服务于去重与抓取目标，有的链接去掉未知参数就打不开。
     *
     * [title] / [description] 留空，见字段注释：抓取来的标题属于页面层，不该在这里存一份快照。
     */
    constructor(rawUrl: String, uid: String, nodeId: String, bookmark: BookmarkEntity) : this(
        uid = uid,
        bookmarkId = bookmark.id,
        urlFull = rawUrl,
        layoutNodeId = nodeId,
        linkType = WebsiteParser.classifyLinkType(bookmark.urlHost),
    )

    constructor(bookmark: BookmarkEntity, nodeId: String, uid: String) : this(
        uid = uid,
        bookmarkId = bookmark.id,
        urlFull = bookmark.rawUrl,
        layoutNodeId = nodeId,
        linkType = WebsiteParser.classifyLinkType(bookmark.urlHost),
    )

    /**
     *  在批量添加自定义书签的时候,用户的自定义书签是确定的,可以批量添加,但是不确定源书签不会在数据库中存在,所以先存储用户的自定义书签,关联书签ID设置为LOADING,
     *  后续对每个源书签单独检查,每检查完一个源书签,就根据源书签host,去找到用户书签的host,将书签ID补上.
     */
    constructor(uid: String, nodeId: String, raw: ChromeBookmarkRawData) : this(
        uid = uid,
        bookmarkId = "LOADING",
        // 浏览器导出的标题就是页面 <title>，长过 200 字符的并不罕见，而 title 列是 varchar(200)。
        // 截断而不是拒绝：标题只是展示数据，丢几个字远好过整批导入被一条长标题带着回滚。
        title = raw.title.take(MAX_TITLE_LENGTH),
        // 同 addOne：存补全协议后的地址，不是原样字符串。导出文件里出现无协议网址虽少见，
        // 但一旦出现，这一列就不是个可跳转的地址了。解析不出来的（javascript: 小书签等）保留原样，
        // 它们本来就要走 parseAndResetUserItem 的「无源书签」收口路径。
        urlFull = normalizeImportedUrl(raw.url),
        layoutNodeId = nodeId,
        linkType = classifyRawLinkType(raw.url),
    )

    companion object {
        /** 对应 `bookmark_user_link.title varchar(200)`（见 deploy/schema.sql）。 */
        const val MAX_TITLE_LENGTH = 200

        private fun classifyRawLinkType(rawUrl: String): BookmarkLinkType =
            runCatching { WebsiteParser.classifyLinkType(WebsiteParser.urlWrapper(rawUrl).urlHost) }
                .getOrDefault(BookmarkLinkType.OTHER)

        private fun normalizeImportedUrl(rawUrl: String): String =
            runCatching { WebsiteParser.urlWrapper(rawUrl).urlRaw }.getOrDefault(rawUrl)
    }
}
