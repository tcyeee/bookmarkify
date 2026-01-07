package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 用户桌面排布
 *
 * @author tcyeee
 * @date 1/7/26 13:37
 */
@TableName("user_layout_node")
data class UserLayoutNodeEntity(
    @TableId @field:Schema(description = "节点ID") val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "父节点ID") val parentId: String = IdUtil.fastUUID(),
    @field:Schema(description = "序号") val sort: Int = Int.MIN_VALUE,
    @field:Schema(description = "书签类型") val type: NodeTypeEnum = NodeTypeEnum.BOOKMARK,

    @field:Schema(description = "用户ID") val uid: String,
    @field:Schema(description = "节点(文件夹)名称") val name: String,
    @field:Schema(description = "添加时间") var createdAt: LocalDateTime = LocalDateTime.now(),

    /* 可选(根据TYPE来决定) */
    @field:Schema(description = "用户自定义书签ID") val bookmarkUserLinkId: String? = null,
    @field:Schema(description = "用户功能ID") val functionId: String? = null,
)

/* 节点类型 */
enum class NodeTypeEnum { BOOKMARK, BOOKMARK_DIR, FUNCTION }