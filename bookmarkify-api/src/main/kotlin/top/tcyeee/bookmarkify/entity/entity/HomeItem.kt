package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.enums.HomeItemType

/**
 * 主页显示的文件夹/APP/设置
 *
 * @author tcyeee
 * @date 4/14/24 15:10
 */
@TableName("home_item")
data class HomeItem(
    @TableId var id: String,
    @field:Schema(description = "用户ID") var uid: String,
    @field:Schema(description = "序号") var sort: Int,
    @field:Schema(description = "书签类型") var type: HomeItemType,

    @JsonIgnore @field:Schema(hidden = true) var bookmarkUserLinkId: String? = null,
    @JsonIgnore @field:Schema(hidden = true) var bookmarkDirJson: String? = null,
    @JsonIgnore @field:Schema(hidden = true) var functionId: Int? = null,
    @JsonIgnore @field:Schema(hidden = true) var deleted: Boolean = false
) {
    constructor( uid: String, linkId: String) : this(
        id = IdUtil.fastUUID(), sort = 99, uid = uid, type = HomeItemType.BOOKMARK,
        bookmarkUserLinkId = linkId
    )
}
