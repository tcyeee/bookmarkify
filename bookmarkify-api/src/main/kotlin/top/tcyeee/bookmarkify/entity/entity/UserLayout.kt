package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.bean.BeanUtil
import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.enums.HomeItemType
import java.time.LocalDateTime
import kotlin.Long

/**
 * 用户桌面排布,只负责结构,不负责数据
 *
 * 排序信息因为涉及到读写的问题: 用过一次就会导致很多信息的排序数据产生变动, 于是排序信息被独立拆分到
 * 用户配置信息中,每次查询的时候先查询桌面布局信息，同时查询用户配置中的排序信息进行组合.
 *
 * @author tcyeee
 * @date 1/7/26 13:37
 */
@TableName("user_layout_node")
data class UserLayoutNodeEntity(
    @TableId @field:Schema(description = "节点ID") val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "父节点ID") val parentId: String? = null,
    @field:Schema(description = "节点类型") val type: NodeTypeEnum = NodeTypeEnum.BOOKMARK,

    @field:Schema(description = "用户ID") val uid: String,
    @field:Schema(description = "节点(文件夹)名称") val name: String? = null,
    @field:Schema(description = "添加时间") var createdAt: LocalDateTime = LocalDateTime.now(),
) {
    // 当前仅仅只包含Bookamrk的展示
    fun vo(sort: Long?, bookmark: BookmarkShow?): UserLayoutNodeVO = UserLayoutNodeVO(
        sort = sort ?: Long.MAX_VALUE,
        typeApp = bookmark
    ).also {
        BeanUtil.copyProperties(this, it)
        if (type == NodeTypeEnum.BOOKMARK) it.typeApp!!.initLogo() // 初始化其中的LOGO
    }

    fun loadingVO(): UserLayoutNodeVO {
        return UserLayoutNodeVO()
    }
}

/* 节点类型 */
enum class NodeTypeEnum {
    /* 书签 */
    BOOKMARK,

    /* 用户新添加书签,但是书签还在等待解析 */
    BOOKMARK_LOADING,

    /* 书签文件夹 */
    BOOKMARK_DIR,

    /* 功能 */
    FUNCTION
}