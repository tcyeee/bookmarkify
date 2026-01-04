package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.utils.CurrentEnvironment
import top.tcyeee.bookmarkify.utils.FileType
import top.tcyeee.bookmarkify.utils.currentEnvironment
import java.io.Serializable
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 12/7/25 15:11
 */
@TableName("user_file")
data class UserFile(
    @JsonIgnore @TableId @field:Schema(description = "文件ID") val id: String = IdUtil.fastUUID(),
    @JsonIgnore @field:Schema(description = "文件所属用户ID") val uid: String,
    @JsonIgnore @field:Schema(description = "文件曾经名称") val originName: String,
    @JsonIgnore @field:Schema(description = "文件大小(单位:字节)") val size: Long,
    @JsonIgnore @field:Schema(description = "文件创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "文件是否被删除") val deleted: Boolean = false,

    @field:Schema(description = "文件类型") val type: FileType,
    @field:Schema(description = "文件所在环境") val environment: CurrentEnvironment = currentEnvironment(),
    @field:Schema(description = "文件当前名称") val currentName: String,
) : Serializable {

    /**
     * 初始化默认背景图像
     * 图片已经上传到OSS中了，位于
     */
    constructor(fileName: String) : this(
        id = fileName.substringBefore("."),
        uid = "system",
        originName = "system_bacground_image",
        size = 1000,
        type = FileType.BACKGROUND,
        currentName = "system_bacground_image"
    )
}
