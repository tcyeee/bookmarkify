package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 12/29/25 15:01
 */
@TableName("website_logo")
data class WebsiteLogoEntity(
    @TableId @field:Schema(description = "文件ID") val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "LOGO所属BookmrkID") val bookmarkId: String,
    @field:Schema(description = "LOGO大小(单位:字节)") val size: Long,
    @field:Schema(description = "LOGO高度(单位:Pix)") val height: Int,
    @field:Schema(description = "LOGO宽度(单位:Pix)") val width: Int,
    @field:Schema(description = "LOGO文件后缀") val suffix: String,
    @field:Schema(description = "LOGO创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "LOGO更新时间") val updateTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "是否为OG展示图") val isOgImg: Boolean = false
) : Serializable {
    /* 通过图标属性判断是否为同样的图标 */
    fun isSame(newLogo: WebsiteLogoEntity): Boolean =
        this.size == newLogo.size &&
                this.width == newLogo.width &&
                this.height == newLogo.height &&
                this.suffix == newLogo.suffix &&
                this.isOgImg == newLogo.isOgImg
}