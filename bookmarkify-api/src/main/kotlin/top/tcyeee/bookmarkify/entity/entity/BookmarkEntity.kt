package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.date.LocalDateTimeUtil
import cn.hutool.core.util.IdUtil
import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.dto.BookmarkWrapper
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.BookmarkLockedField
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.utils.ChromeBookmarkRawData
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
    @field:Max(200) @field:Schema(description = "书签根域名") var urlHost: String,        // sfz.uzuzuz.com.cn
    // canonical 书签按 (urlHost, urlPath) 联合去重/抓取，而不是只按 urlHost：
    // 同一域名下不同路径（如不同 GitHub 仓库、不同 Notion 页面）是完全不同的页面，
    // 各自应有自己的标题/简称/图标，不能共用同一条记录。根路径统一存 "/"。
    @field:Max(500) @field:Schema(description = "书签路径(与 urlHost 联合构成去重/抓取的唯一标识)") var urlPath: String = "/",
    @field:Max(10) @field:Schema(description = "书签基础HTTP协议") var urlScheme: String, // http or https

    /* 基础信息 */
    @field:Max(100) @field:Schema(description = "书签简称") var appName: String? = null,
    @field:Max(200) @field:Schema(description = "书签标题") var title: String? = null,
    @field:Max(1000) @JsonIgnore @field:Schema(description = "书签备注") var description: String? = null,

    // 图标相关信息已迁往 site_asset / site_display_pref：一行一图 + 按展示模式分行的显示偏好。

    /* 状态信息 */
    @JsonIgnore @field:Schema(description = "是否解析成功") var parseStatus: ParseStatusEnum = ParseStatusEnum.PENDING,
    @JsonIgnore @field:Schema(description = "网站是否活跃") var isActivity: Boolean = false,
    @JsonIgnore @field:Schema(description = "抓取成功但页面疑似反爬虫/WAF挑战页,内容可能不可靠") var antiCrawlerBlocked: Boolean = false,
    @JsonIgnore @field:Schema(description = "手动认证状态") var verifyFlag: Boolean = false, // 如果该书签信息都没问题, 添加手动认证状态以后, 即可被搜索到
    @field:Schema(description = "疑似涉黄/涉赌等违规内容(NSFW)，由 DeepSeek 判断") var nsfw: Boolean = false,
    @JsonIgnore @field:Max(50) @field:Schema(description = "NSFW 判定理由，由 DeepSeek 给出，供人工审核/排查使用") var nsfwReason: String? = null,
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
    @field:Max(200)
    @field:Schema(description = "管理员手工锁定、不允许自动抓取覆盖的字段(逗号分隔)")
    var lockedFields: String? = null,
) {
    // 拼接后的完整网站（含路径，抓取/ping 都以这个具体页面为目标，而不是域名根路径）
    val rawUrl get() = "${this.urlScheme}://${this.urlHost}${this.urlPath}"

    // JSON格式化后的数据
    val json: String? get() = JSONUtil.toJsonStr(this)

    // 通过URL初始化
    constructor(url: BookmarkUrlWrapper) : this(
        id = IdUtil.fastUUID(),
        urlHost = url.urlHost,
        urlPath = url.urlPath ?: "/",
        urlScheme = url.urlScheme,
        parseStatus = ParseStatusEnum.PENDING,
    )

    constructor(chromeRowDate: ChromeBookmarkRawData) : this(
        id = IdUtil.fastUUID(),
        title = chromeRowDate.title,
        urlHost = "",
        urlScheme = "",
    ) {
        val bookmarkUrl: BookmarkUrlWrapper = WebsiteParser.urlWrapper(chromeRowDate.url)
        urlHost = bookmarkUrl.urlHost
        urlPath = bookmarkUrl.urlPath ?: "/"
        urlScheme = bookmarkUrl.urlScheme
    }

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
    val lockedFieldSet: Set<BookmarkLockedField>
        get() = lockedFields?.split(',')
            ?.mapNotNull { raw -> BookmarkLockedField.entries.firstOrNull { it.name == raw.trim() } }
            ?.toSet()
            ?: emptySet()

    fun isLocked(field: BookmarkLockedField): Boolean = field in lockedFieldSet

    /** 加锁（管理员手工编辑了该字段）。 */
    fun lock(vararg fields: BookmarkLockedField) = setLockedFields(lockedFieldSet + fields)

    /** 解锁（管理员显式接受了抓取来的值，该字段此后不再是人工值）。 */
    fun unlock(vararg fields: BookmarkLockedField) = setLockedFields(lockedFieldSet - fields.toSet())

    private fun setLockedFields(fields: Set<BookmarkLockedField>) {
        // 空集合存 NULL 而不是空字符串：省得查询和判空要同时处理两种"没有锁"的表示
        lockedFields = fields.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
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
    @field:Max(40) @field:Schema(description = "用户ID") var uid: String,
    @field:Max(40) @field:Schema(description = "书签ID") val bookmarkId: String?,  // 书签ID可能为null,在用户批量添加的时候,只会添加用户自定义书签,而不会关联到源书签
    @field:Max(40) @field:Schema(description = "用户桌面排布ID") val layoutNodeId: String,

    @field:Max(200) @field:Schema(description = "书签标题(用户写的)") val title: String? = null,
    @field:Max(1000) @field:Schema(description = "书签备注(用户写的)") val description: String? = null,
    @field:Max(1000) @field:Schema(description = "书签完整URL(带参数)") val urlFull: String,    // http://sfz.uzuzuz.com.cn/?region=150303%26birthday=19520807%26sex=2%26num=19%26r=82,
    @field:Schema(description = "书签链接类型(域名/本地/IP/其他)") var linkType: BookmarkLinkType = BookmarkLinkType.OTHER,

    @field:Schema(description = "是否置顶") var pinned: Boolean = false,
    @JsonIgnore @field:Schema(description = "用户打开该书签的累计次数(仅做记录)") var openCount: Int = 0,

    @JsonIgnore @field:Schema(description = "创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "是否删除") val deleted: Boolean = false,
) {
    constructor(rawUrl: String, uid: String, nodeId: String, bookmark: BookmarkEntity) : this(
        uid = uid,
        bookmarkId = bookmark.id,
        title = bookmark.title,
        description = bookmark.description,
        urlFull = rawUrl,
        layoutNodeId = nodeId,
        linkType = WebsiteParser.classifyLinkType(bookmark.urlHost),
    )

    constructor(bookmark: BookmarkEntity, nodeId: String, uid: String) : this(
        uid = uid,
        bookmarkId = bookmark.id,
        title = bookmark.title,
        description = bookmark.description,
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
        title = raw.title,
        urlFull = raw.url,
        layoutNodeId = nodeId,
        linkType = classifyRawLinkType(raw.url),
    )

    companion object {
        private fun classifyRawLinkType(rawUrl: String): BookmarkLinkType =
            runCatching { WebsiteParser.classifyLinkType(WebsiteParser.urlWrapper(rawUrl).urlHost) }
                .getOrDefault(BookmarkLinkType.OTHER)
    }
}
