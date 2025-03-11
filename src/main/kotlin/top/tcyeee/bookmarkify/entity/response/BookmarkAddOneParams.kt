package top.tcyeee.bookmarkify.entity.response

import io.swagger.v3.oas.annotations.media.Schema

/**
 * @author tcyeee
 * @date 4/17/24 21:34
 */
data class BookmarkAddOneParams(
    @Schema(description = "添加的域名") var url: String,
)
