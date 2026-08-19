package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName

/** [SystemCollectionEntity] 与 [PageEntity] 的关联行，`sort` 决定集合内的展示顺序。 */
@TableName("system_collection_page")
data class SystemCollectionPageEntity(
    @TableId var id: String = IdUtil.fastUUID(),
    var collectionId: String = "",
    var pageId: String = "",
    var sort: Int = 0,
)
