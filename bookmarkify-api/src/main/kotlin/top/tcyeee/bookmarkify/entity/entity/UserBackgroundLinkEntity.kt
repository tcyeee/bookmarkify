package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.request.UpdateBackgroundParams
import java.time.LocalDateTime

/**
 * 用户背景图片设置
 *
 * @author tcyeee
 * @date 12/7/25 14:39
 */

enum class BackgroundType {
    /* 渐变色背景 */
    GRADIENT,

    /* 图片背景 */
    IMAGE,
}

@TableName("user_background_link")
class UserBackgroundLinkEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "用户ID") var uid: String,
    @field:Schema(description = "背景类型") var type: BackgroundType,
    @field:Schema(description = "背景ID") var backgroundLinkId: String,
    @field:Schema(description = "创建时间") var updateTime: LocalDateTime = LocalDateTime.now(),
) {
    fun updateParams(params: UpdateBackgroundParams) {
        this.type = params.type
        this.backgroundLinkId = params.backgroundId
        this.updateTime = LocalDateTime.now()
    }
}

@TableName("image_background")
class ImageBackgroundEntity(
    @TableId @field:Schema(description = "背景ID") var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "用户ID") val uid: String,
    @field:Schema(description = "文件ID") val fileId: String,
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