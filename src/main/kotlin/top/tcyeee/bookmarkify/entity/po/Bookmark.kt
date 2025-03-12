package top.tcyeee.bookmarkify.entity.po

import cn.hutool.core.date.LocalDateTimeUtil
import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.IdUtil
import cn.hutool.core.util.StrUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrl
import java.io.File
import java.time.LocalDateTime

/**
 * 书签相关
 *
 * @author tcyeee
 * @date 3/10/24 15:31
 */
@TableName("bookmark")
data class Bookmark(
    @TableId var id: String,
    @Max(200) @Schema(description = "书签URL主体") var urlHost: String, // sfz.uzuzuz.com.cn
    @JsonIgnore @Schema(description = "书签完整URL(不带参数)") var urlPath: String, // /test/info

    @Max(200) @Schema(description = "书签标题") var title: String? = null,
    @JsonIgnore @Schema(description = "书签评分0~10") var score: Int? = null,
    @Max(1000) @JsonIgnore @Schema(description = "书签备注") var description: String? = null,
    @Max(10) @Schema(description = "书签基础HTTP协议") var urlScheme: String? = null, // http or https
    @Schema(description = "需要特别指定的图标地址") var iconUrl: String? = null,

    @JsonIgnore @Schema(description = "是否失效") var isActivity: Boolean = false,
    @Schema(description = "图标是否存在") var iconActivity: Boolean = false,
    @Schema(description = "是否可以启用大图标") var iconHd: Boolean = false,
    @JsonIgnore @Schema(description = "添加时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @Schema(description = "最近更新时间") var updateTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @Schema(description = "是否已经被删除") var deleted: Boolean = false,
) {
    fun fullUrl() = "${urlScheme}//${urlHost}"

    fun addIcon(logo: File?) {
        val filePath = String.format("/favicon/%s.%s", this.id, FileUtil.extName(logo))
        this.iconActivity = logo != null
        this.updateTime = LocalDateTime.now()
        this.iconUrl = if (logo != null) null else filePath
    }

    fun checkActity(isActivity: Boolean) {
        this.isActivity = isActivity
        this.updateTime = LocalDateTime.now()
    }

    constructor(url: BookmarkUrl) : this(
        id = IdUtil.fastUUID(),
        urlHost = url.urlHost,
        urlScheme = url.urlScheme,
        urlPath = url.urlPath,
    )

    constructor(url: BookmarkUrl, addDate: String, name: String) : this(
        id = IdUtil.fastUUID(),
        urlHost = url.urlHost,
        urlScheme = url.urlScheme,
        urlPath = url.urlPath,
        title = name,
        createTime = if (StrUtil.isNotBlank(addDate)) LocalDateTimeUtil.of(addDate.toLong() * 1000) else LocalDateTime.now()
    )
}