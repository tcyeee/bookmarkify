package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.BacGradientVO
import top.tcyeee.bookmarkify.entity.BacSettingVO
import top.tcyeee.bookmarkify.entity.BackSettingParams
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

@TableName("background_config")
data class BackgroundConfigEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "用户ID") var uid: String,
    @field:Schema(description = "背景类型") var type: BackgroundType,
    @field:Schema(description = "背景ID") var backgroundLinkId: String,
    @field:Schema(description = "创建时间") var updateTime: LocalDateTime = LocalDateTime.now(),
) {
    fun updateParams(params: BackSettingParams) {
        this.type = params.type
        this.backgroundLinkId = params.backgroundId
        this.updateTime = LocalDateTime.now()
    }

    fun vo(): BacSettingVO = BacSettingVO(uid = this.uid, type = this.type, backgroundLinkId = this.backgroundLinkId)
}

@TableName("background_image")
data class BackgroundImageEntity(
    @TableId @field:Schema(description = "背景ID") var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "用户ID") val uid: String,
    @field:Schema(description = "文件ID") val fileId: String,
    @field:Schema(description = "创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "是否是默认展示的背景") val isDefault: Boolean = false,
)

@TableName("background_gradient")
data class BackgroundGradientEntity(
    @TableId @field:Schema(description = "背景ID") val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "用户ID") var uid: String,
    @field:Schema(description = "颜色名称") val name: String? = null,
    @field:Schema(description = "创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "是否是默认展示的背景") val isDefault: Boolean = false,
    @field:Schema(description = "背景渐变色(JSON)") var gradient: String,
    @field:Schema(description = "背景渐变方向") var direction: Int,
) {
    fun gradientArray(): Array<String> =
        this.gradient.trim().removePrefix("[").removeSuffix("]").split(",")
            .map { it.trim().removeSurrounding("\"") }.toTypedArray()

    fun vo(): BacGradientVO = BacGradientVO(id = this.id, colors = this.gradientArray(), direction = this.direction)
}