package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
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
    @field:Max(200) @field:Schema(description = "书签URL主体") var urlHost: String,       // sfz.uzuzuz.com.cn
    @field:Schema(description = "书签完整URL(不带参数)") var urlPath: String? = null,              // /test/info
    @field:Max(10) @field:Schema(description = "书签基础HTTP协议") var urlScheme: String, // http or https

    /* 其他 */
    @field:Max(200) @field:Schema(description = "书签标题") var title: String? = null,
    @JsonIgnore @field:Schema(description = "书签评分0~10") var score: Int? = null,
    @field:Max(1000) @JsonIgnore @field:Schema(description = "书签备注") var description: String? = null,
    @field:Max(100) @field:Schema(description = "图标链接") var iconUrl: String? = null,

    @JsonIgnore @field:Schema(description = "是否被成功解析") var parseFlag: Boolean = false,
    @JsonIgnore @field:Schema(description = "是否失效") var isActivity: Boolean = false,
    @field:Schema(description = "图标是否存在") var iconActivity: Boolean = false,
    @field:Schema(description = "是否可以启用大图标") var iconHd: Boolean = false,
    @JsonIgnore @field:Schema(description = "添加时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "最近更新时间") var updateTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "是否已经被删除") var deleted: Boolean = false,
) {
//    val httpCommonIcoUrl get() = "${this.urlScheme}://${this.urlHost}/favicon.ico"
//    val defaultIconUrl get() = "/favicon/${this.id}.ico"
    val rawUrl get() = "${this.urlScheme}://${this.urlHost}"

    constructor(url: BookmarkUrlWrapper) : this(
        id = IdUtil.fastUUID(),
        urlHost = url.urlHost,
        urlScheme = url.urlScheme,
        urlPath = url.urlPath,
    )

//    constructor(url: BookmarkUrlWrapper, addDate: String, name: String) : this(
//        id = IdUtil.fastUUID(),
//        urlHost = url.urlHost,
//        urlScheme = url.urlScheme,
//        urlPath = url.urlPath,
//        title = name,
//        createTime = if (StrUtil.isNotBlank(addDate)) LocalDateTimeUtil.of(addDate.toLong() * 1000) else LocalDateTime.now()
//    )

    fun checkActivity(isActivity: Boolean) {
        this.isActivity = isActivity
        this.updateTime = LocalDateTime.now()
    }
}