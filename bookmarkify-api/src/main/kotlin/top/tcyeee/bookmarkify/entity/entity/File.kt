package top.tcyeee.bookmarkify.entity.entity

import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 12/7/25 15:11
 */
@TableName("file")
data class File(
    @TableId @field:Schema(description = "文件ID") var id: String,
    @field:Schema(description = "文件名称") val name: String,
    @field:Schema(description = "文件路径") val path: String,
    @field:Schema(description = "文件类型") val type: String,
    @field:Schema(description = "文件大小") val size: Long,
    @field:Schema(description = "文件创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "文件更新时间") val updateTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "文件是否被删除") val deleted: Boolean = false,
)