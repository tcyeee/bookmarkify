package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.dto.BookmarkWrapper
import top.tcyeee.bookmarkify.utils.FileUtils
import top.tcyeee.bookmarkify.utils.yesterday
import java.io.Serializable
import java.time.LocalDateTime

/**
 * 书签相关
 *
 * @author tcyeee
 * @date 3/10/24 15:31
 */
@TableName("bookmark")
data class Bookmark(

    /* URL相关 */
    @TableId var id: String,
    @field:Max(200) @field:Schema(description = "书签根域名") var urlHost: String,      // sfz.uzuzuz.com.cn
    @field:Schema(description = "路径URL(不带参数)") var urlPath: String? = null,         // /test/info
    @field:Max(10) @field:Schema(description = "书签基础HTTP协议") var urlScheme: String, // http or https

    /* 基础信息 */
    @field:Max(100) @field:Schema(description = "书签简称") var appName: String? = null,
    @field:Max(200) @field:Schema(description = "书签标题") var title: String? = null,
    @field:Max(1000) @JsonIgnore @field:Schema(description = "书签备注") var description: String? = null,
    @field:Schema(description = "小图标base64") var iconBase64: String? = null,
    @field:Schema(description = "最大LOGO尺寸") var maximalLogoSize: Int = 0,

    /* 状态信息 */
    @JsonIgnore @field:Schema(description = "是否反爬") var antiCrawlerDetected: Boolean = false,
    @JsonIgnore @field:Schema(description = "网站是否活跃") var isActivity: Boolean = false,
    @JsonIgnore @field:Schema(description = "解析失败后的反馈") var parseErrMsg: String? = null,
    @JsonIgnore @field:Schema(description = "添加时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "最近更新时间") var updateTime: LocalDateTime? = null,  // 最近更新时间创建的时候默认为null,表示是刚创建的
) {
    val rawUrl get() = "${this.urlScheme}://${this.urlHost}"

    // 检查标签,这里为true,说明这个书签需要被检查了
    val checkFlag get() = updateTime?.isBefore(yesterday()) ?: true

    constructor(url: BookmarkUrlWrapper) : this(
        id = IdUtil.fastUUID(),
        urlHost = url.urlHost,
        urlScheme = url.urlScheme,
        urlPath = url.urlPath,
    )

    fun initBaseInfo(wrapper: BookmarkWrapper) {
        this.appName = wrapper.name  // TODO 使用AI将title中的信息简化
        this.title = wrapper.title
        this.description = wrapper.description
        this.antiCrawlerDetected = wrapper.antiCrawlerDetected
        this.updateTime = LocalDateTime.now()
        this.iconBase64 = FileUtils.icoBase64(wrapper.distinctIcons, this.rawUrl)
    }
}

@TableName("bookmark_user_link")
data class BookmarkUserLink(
    @TableId var id: String,
    @field:Max(40) @field:Schema(description = "用户ID") var uid: String,
    @field:Max(40) @field:Schema(description = "书签ID") var bookmarkId: String,
    @field:Max(200) @field:Schema(description = "书签标题(用户写的)") var title: String?,
    @field:Max(1000) @field:Schema(description = "书签备注(用户写的)") var description: String?,
    @field:Max(1000) @field:Schema(description = "书签完整URL(带参数)") var urlFull: String,    // http://sfz.uzuzuz.com.cn/?region=150303%26birthday=19520807%26sex=2%26num=19%26r=82,

    @JsonIgnore @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "是否删除") var deleted: Boolean = false,
) {
    constructor(bookmarkUrlWrapper: BookmarkUrlWrapper, uid: String, bookmark: Bookmark) : this(
        id = IdUtil.fastUUID(),
        uid = uid,
        bookmarkId = bookmark.id,
        title = bookmark.title,
        description = bookmark.description,
        urlFull = bookmarkUrlWrapper.urlRaw,
    )

    constructor(bookmark: Bookmark, uid: String) : this(
        id = IdUtil.fastUUID(),
        uid = uid,
        bookmarkId = bookmark.id,
        title = bookmark.title,
        description = bookmark.description,
        urlFull = bookmark.rawUrl,
    )
}

// ICON or OG
@TableName("website_logo")
data class WebsiteLogoEntity(
    @TableId @field:Schema(description = "文件ID") val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "LOGO所属BookmrkID") val bookmarkId: String,
    @field:Schema(description = "LOGO大小(单位:字节)") val size: Long,
    @field:Schema(description = "LOGO高度(单位:Pix)") val height: Int,
    @field:Schema(description = "LOGO宽度(单位:Pix)") val width: Int,
    @field:Schema(description = "LOGO文件后缀") val suffix: String,
    @field:Schema(description = "LOGO创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "LOGO更新时间") val updateTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "是否为OG展示图") val isOgImg: Boolean = false
) : Serializable