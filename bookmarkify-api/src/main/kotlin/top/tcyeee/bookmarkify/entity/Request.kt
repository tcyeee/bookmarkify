package top.tcyeee.bookmarkify.entity

import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.entity.BackgroundType


/**
 * 选择主页背景入参
 */
data class BackSettingParams(
    @field:Schema(description = "背景类型：GRADIENT / IMAGE")
    val type: BackgroundType,

    @field:Schema(description = "背景ID")
    val backgroundId: String,
)

data class UserDelParams(
    val password: String
)

data class UserInfoUptateParams (
    var nickName: String,
    var phone: String?
)