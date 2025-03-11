package top.tcyeee.bookmarkify.entity.po

import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 3/10/24 16:23
 */
@TableName("bookmark_user_link")
data class BookmarkUserLink(
    @TableId var id: String,
    @Max(40) @Schema(description = "用户ID") var uid: String,
    @Max(40) @Schema(description = "书签ID") var bookmarkId: String,
    @Max(200) @Schema(description = "书签标题(用户写的)") var title: String,
    @Max(1000) @Schema(description = "书签备注(用户写的)") var description: String,
    @Max(1000) @Schema(description = "书签完整URL(带参数)") var urlFull: String, // http://sfz.uzuzuz.com.cn/?region=150303%26birthday=19520807%26sex=2%26num=19%26r=82,
    @JsonIgnore @Schema(description = "创建时间") var createTime: LocalDateTime,
    @JsonIgnore @Schema(description = "是否删除") var deleted: Boolean,
)