package top.tcyeee.bookmarkify.entity.common

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 主页背景相关配置
 */
enum class BackgroundType {
    GRADIENT,
    IMAGE,
}

data class GradientConfig(
    @field:Schema(description = "渐变色数组，至少2个颜色")
    var colors: List<String> = emptyList(),

    @field:Schema(description = "渐变方向角度，默认135")
    var direction: Int? = 135,
)

data class BackgroundConfig(
    @field:Schema(description = "背景类型")
    var type: BackgroundType,

    @field:Schema(description = "渐变配置，当 type 为 GRADIENT 时使用")
    var gradient: GradientConfig? = null,

    @field:Schema(description = "图片路径，当 type 为 IMAGE 时使用")
    var imagePath: String? = null,
)

