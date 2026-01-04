package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.UserFileVO
import top.tcyeee.bookmarkify.utils.CurrentEnvironment
import top.tcyeee.bookmarkify.utils.FileType
import top.tcyeee.bookmarkify.utils.OssUtils
import top.tcyeee.bookmarkify.utils.currentEnvironment
import java.io.Serializable
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 12/7/25 15:11
 */
@TableName("user_file")
data class UserFile(
    @TableId @field:Schema(description = "文件ID") val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "文件所属用户ID") val uid: String,
    @field:Schema(description = "文件曾经名称") val originName: String,
    @field:Schema(description = "文件大小(单位:字节)") val size: Long,
    @field:Schema(description = "文件创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "文件是否被删除") val deleted: Boolean = false,
    @field:Schema(description = "文件类型") val type: FileType,
    @field:Schema(description = "文件所在环境") val environment: CurrentEnvironment = currentEnvironment(),
    @field:Schema(description = "文件当前名称") val currentName: String,   // eg: 019b86fd-74af-7058-829f-3f580c54c1e8
    @field:Schema(description = "文件后缀") val suffix: String,        // eg: png
) : Serializable {
    val fullPath: String = "${type.folder}/$currentName.$suffix"

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
        currentName = "system_bacground_image",
        suffix = "png"
    )

    fun vo(): UserFileVO = UserFileVO(
        id = id,
        fullName = OssUtils.resizeAndSignImg(fullPath, 100, 100),
    )

    fun fullUrlWithSign(size: Int): String = OssUtils.resizeAndSignImg(fullPath, size, size)
}

