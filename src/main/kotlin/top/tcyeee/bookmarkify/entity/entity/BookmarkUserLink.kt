package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrl
import top.tcyeee.bookmarkify.entity.response.BookmarkDetail
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
    @Max(200) @Schema(description = "书签标题(用户写的)") var title: String?,
    @Max(1000) @Schema(description = "书签备注(用户写的)") var description: String?,
    @Max(1000) @Schema(description = "书签完整URL(带参数)") var urlFull: String,    // http://sfz.uzuzuz.com.cn/?region=150303%26birthday=19520807%26sex=2%26num=19%26r=82,

    @JsonIgnore @Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @Schema(description = "是否删除") var deleted: Boolean = false,
) {

    constructor(item: BookmarkDetail, uid: String) : this(
        id = IdUtil.fastUUID(),
        uid = uid,
        bookmarkId = item.bookmark.id,
        title = item.bookmark.title,
        description = item.bookmark.description,
        urlFull = item.url.urlFull
    )

    constructor(bookmarkUrl: BookmarkUrl, uid: String, bookmark: Bookmark) : this(
        id = IdUtil.fastUUID(),
        uid = uid,
        bookmarkId = bookmark.id,
        title = bookmark.title,
        description = bookmark.description,
        urlFull = bookmarkUrl.urlFull,
    )
}