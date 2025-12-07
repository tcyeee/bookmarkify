package top.tcyeee.bookmarkify.entity.request

import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.common.BackgroundType

/**
 * 选择主页背景入参
 */
data class UserBackgroundSelectParams(
    @field:Schema(description = "背景类型：GRADIENT / IMAGE")
    val type: BackgroundType,

    @field:Schema(description = "背景ID")
    val backgroundId: String,
)
