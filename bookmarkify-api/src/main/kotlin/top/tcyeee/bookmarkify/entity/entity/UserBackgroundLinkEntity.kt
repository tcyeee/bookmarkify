package top.tcyeee.bookmarkify.entity.entity

import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 用户背景图片设置
 *
 * @author tcyeee
 * @date 12/7/25 14:39
 */

enum class BackgroundType {
    GRADIENT,
    IMAGE,
}

@TableName("user_background_link")
class UserBackgroundLinkEntity(
    @TableId var id: String,
    @field:Schema(description = "用户ID") val uid: String,
    @field:Schema(description = "背景类型") val type: BackgroundType,
    @field:Schema(description = "背景ID") val backgroundId: String,
    @field:Schema(description = "创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
)

@TableName("image_background")
class ImageBackgroundEntity(
    @TableId @field:Schema(description = "背景ID") var id: String,
    @field:Schema(description = "用户ID") val uid: String,
    @field:Schema(description = "背景图片Path") val imagePath: String,
    @field:Schema(description = "创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "是否是默认展示的背景") val isDefault: Boolean = false,
)

@TableName("gradient_background")
class GradientBackgroundEntity(
    @TableId @field:Schema(description = "背景ID") var id: String,
    @field:Schema(description = "用户ID") val uid: String,
    @field:Schema(description = "背景渐变色") val gradient: String,
    @field:Schema(description = "背景渐变方向") val direction: Int,
    @field:Schema(description = "创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "是否是默认展示的背景") val isDefault: Boolean = false,
)