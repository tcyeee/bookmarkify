package top.tcyeee.bookmarkify.entity.entity

import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 3/10/24 21:56
 */
@TableName("bookmark_tag")
data class BookmarkTag(
    @TableId var id: String,
    @Max(200) @Schema(description = "标签名称") var name: String,
    @Schema(description = "所属用户ID") var uid: String,
    @Max(1000) @Schema(description = "标签备注") var description: String,
    @Max(10) @Schema(description = "标签颜色") var color: String,

    @JsonIgnore @Schema(description = "是否已经被删除") var deleted: Boolean,
    @JsonIgnore @Schema(description = "添加时间") var createTime: LocalDateTime,
    @JsonIgnore @Schema(description = "上次修改时间") var lastModified: LocalDateTime,
)