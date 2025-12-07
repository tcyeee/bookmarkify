package top.tcyeee.bookmarkify.entity.entity

import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 3/10/24 21:56
 */
@TableName("bookmark_tag_link")
data class BookmarkTagLink(
    @TableId var id: String,
    @field:Schema(description = "标签ID") var tagId: String,
    @field:Schema(description = "关联的书签ID") var bookmarkId: String,
    @field:Schema(description = "所属用户ID") var uid: String,

    @JsonIgnore @field:Schema(description = "添加时间") var createTime: LocalDateTime,
    @JsonIgnore @field:Schema(description = "是否删除") var deleted: Boolean,
)