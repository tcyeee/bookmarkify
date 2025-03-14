package top.tcyeee.bookmarkify.entity.po

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
    @Schema(description = "用户ID") var uid: String,
    @Schema(description = "序号") var sort: Int,
    @Schema(description = "书签类型") var type: HomeItemType,

    @JsonIgnore @Schema(hidden = true) var bookmarkUserLinkId: String? = null,
    @JsonIgnore @Schema(hidden = true) var bookmarkDirJson: String? = null,
    @JsonIgnore @Schema(hidden = true) var functionId: Int? = null,
    @JsonIgnore @Schema(hidden = true) var deleted: Boolean = false
) {

    // 通过书签信息创建
    constructor(bookmark: Bookmark, uid: String, linkId: String) : this(
        id = IdUtil.fastUUID(), sort = 99, uid = uid, type = HomeItemType.BOOKMARK,
        bookmarkUserLinkId = linkId
    )

    constructor(bookmarkIds: List<String>, dirTitle: String, uid: String) : this(
        id = IdUtil.fastUUID(),
        sort = 10,
        uid = uid,
        type = HomeItemType.BOOKMARK_DIR,
    )
}
