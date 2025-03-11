package top.tcyeee.bookmarkify.entity.response

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 其中数据将存储在前端页面
 *
 * @author tcyeee
 * @date 1/21/24 23:31
 */
data class UserStore(
    @Schema(description = "nickName") var nickName: String,
    @Schema(description = "mail") var mail: String,
    @Schema(description = "TOKEN") var token: String,
    var uid: String,
)