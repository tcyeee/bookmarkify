package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.utils.CurrentEnvironment
import top.tcyeee.bookmarkify.utils.currentEnvironment
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 12/7/25 15:11
 */
@TableName("user_file")
class UserFile(
    @TableId @field:Schema(description = "文件ID") val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "文件所属用户ID") val uid: String,
    @field:Schema(description = "文件所在环境") val environment: CurrentEnvironment = currentEnvironment(),
    @field:Schema(description = "文件名称") val name: String,
    @field:Schema(description = "文件类型") val type: UserFileType,
    @field:Schema(description = "文件大小(单位:字节)") val size: Long,
    @field:Schema(description = "文件创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "文件更新时间") val updateTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "文件是否被删除") val deleted: Boolean = false,
)

enum class UserFileType {
    /* 头像图片 */
    AVATAR_IMAGE,

    /* 背景图片 */
    BACKGROUND_IMAGE,

    /* 其他文件 */
    OTHER_FILE,
}