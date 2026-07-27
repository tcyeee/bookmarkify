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

    // 图标相关字段（小图标/高清LOGO/内边距/背景色/高清开关）已迁移到 website_logo 表（WebsiteLogoEntity），与书签一对一。

    /* 状态信息 */
    @JsonIgnore @field:Schema(description = "是否解析成功") var parseStatus: ParseStatusEnum = ParseStatusEnum.LOADING,
    @JsonIgnore @field:Schema(description = "网站是否活跃") var isActivity: Boolean = false,
    @JsonIgnore @field:Schema(description = "手动认证状态") var verifyFlag: Boolean = false, // 如果该书签信息都没问题, 添加手动认证状态以后, 即可被搜索到
    @JsonIgnore @field:Schema(description = "解析失败后的反馈") var parseErrMsg: String? = null,
    @JsonIgnore @field:Schema(description = "添加时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "最近更新时间") var updateTime: LocalDateTime? = null,  // 最近更新时间创建的时候默认为null,表示是刚创建的
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
        parseStatus = ParseStatusEnum.LOADING,
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
        this.parseStatus = if (wrapper.antiCrawlerDetected) ParseStatusEnum.BLOCKED else ParseStatusEnum.SUCCESS
        this.updateTime = LocalDateTime.now()
        // 小图标(iconBase64)已迁出到 website_logo，由解析层在保存元信息后单独 upsert。
    }

    // 是否需要检查标签,这里为true,说明这个书签需要被检查了
    fun checkFlag(): Boolean {
        if (updateTime == null) return true
        return LocalDateTimeUtil.between(updateTime, LocalDateTime.now(), ChronoUnit.DAYS) > 1
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

/**
 * 书签图标记录：小图标(base64) + 高清LOGO + 显示设置(内边距/背景色/高清开关) + 高清LOGO文件元数据。
 * 与 bookmark 一对一（bookmark_id 唯一）。这些图标相关字段原先在 bookmark 表，现统一收拢到此表。
 */
@TableName("website_logo")
data class WebsiteLogoEntity(
    @TableId @field:Schema(description = "图标记录ID") val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "所属书签ID") var bookmarkId: String = "",

    /* 图标数据 */
    @field:Schema(description = "小图标base64") var iconBase64: String? = null,
    @field:Max(500) @field:Schema(description = "高清LOGO的OSS地址") var logoUrl: String? = null,

    /* 显示设置（管理后台可编辑） */
    @field:Schema(description = "图片内边距") var iconPadding: Int = 25,
    @field:Max(32) @field:Schema(description = "图标背景色") var iconBgColor: String? = null,
    @field:Schema(description = "是否在前台用高清LOGO渲染") var useHdLogo: Boolean = false,

    /* 高清LOGO文件元数据 */
    @field:Schema(description = "LOGO大小(单位:字节)") var size: Long = 0,
    @field:Schema(description = "LOGO高度(单位:Pix)") var height: Int = 0,
    @field:Schema(description = "LOGO宽度(单位:Pix)") var width: Int = 0,
    @field:Schema(description = "LOGO文件后缀") var suffix: String = "",

    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "更新时间") var updateTime: LocalDateTime = LocalDateTime.now(),
) : Serializable