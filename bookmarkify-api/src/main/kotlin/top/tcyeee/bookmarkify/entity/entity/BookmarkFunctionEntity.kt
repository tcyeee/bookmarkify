package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import top.tcyeee.bookmarkify.entity.BookmarkFunctionVO
import java.time.LocalDateTime

/**
 * 系统功能和用户的关联
 * 系统功能是写在枚举中的,数据库并没有存值
 *
 * @author tcyeee
 * @date 1/12/26 12:13
 */
@TableName("bookmark_function")
data class BookmarkFunctionEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Max(40) @field:Schema(description = "用户ID") val uid: String,
    @field:Max(40) @field:Schema(description = "用户桌面排布ID") val layoutNodeId: String,

    @field:Schema(description = "功能类型") val type: FunctionType,
    @field:Schema(description = "创建时间") val createAt: LocalDateTime = LocalDateTime.now(),
) {
    constructor(node: UserLayoutNodeEntity, uid: String) : this(
        uid = uid,
        type = FunctionType.SETTING,
        layoutNodeId = node.id,
    )

    fun vo(): BookmarkFunctionVO = BookmarkFunctionVO(
        id = this.id,
        layoutNodeId = this.layoutNodeId,
        type = this.type,
    )
}

enum class FunctionType { SETTING }