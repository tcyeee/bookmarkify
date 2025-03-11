package top.tcyeee.bookmarkify.entity.po

import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
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
    @Max(200) @Schema(description = "书签标题") var title: String,
    @Max(1000) @JsonIgnore @Schema(description = "书签备注") var description: String,
    @JsonIgnore @Schema(description = "书签评分0~10") var score: Double,
    @Max(10) @Schema(description = "书签基础HTTP协议") var urlScheme: String, // http or https
    @Max(200) @Schema(description = "书签URL主体") var urlHost: String, // sfz.uzuzuz.com.cn
    @JsonIgnore @Schema(description = "书签完整URL(不带参数)") var urlPath: String, // /test/info
    @JsonIgnore @Schema(description = "是否失效") var isActivity: Boolean = false,
    @Schema(description = "需要特别指定的图标地址") var iconUrl: String,
    @Schema(description = "图标是否存在") var iconActivity: Boolean = false,
    @Schema(description = "是否可以启用大图标") var iconHd: Boolean = false,
    @JsonIgnore @Schema(description = "添加时间") var createTime: LocalDateTime,
    @JsonIgnore @Schema(description = "最近更新时间") var updateTime: LocalDateTime,
    @JsonIgnore @Schema(description = "是否已经被删除") var deleted: Boolean = false,
)