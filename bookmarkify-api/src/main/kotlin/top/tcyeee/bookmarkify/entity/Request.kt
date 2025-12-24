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

data class BookmarkUpdataPrams(
    var linkId: String,
    var title: String,
    var description: String,
)

data class GradientConfigParams(
    @field:Schema(description = "渐变色数组，至少2个颜色")
    var colors: List<String> = emptyList(),

    @field:Schema(description = "渐变方向角度，默认135")
    var direction: Int = 135,
)

data class CaptchaSmsParams(val phone: String, val captcha: String)
data class SmsVerifyParams(val phone: String, val smsCode: String)
