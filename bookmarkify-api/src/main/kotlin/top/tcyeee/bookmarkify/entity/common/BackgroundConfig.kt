package top.tcyeee.bookmarkify.entity.common

import io.swagger.v3.oas.annotations.media.Schema

data class GradientConfig(
    @field:Schema(description = "渐变色数组，至少2个颜色")
    var colors: List<String> = emptyList(),

    @field:Schema(description = "渐变方向角度，默认135")
    var direction: Int = 135,
)

