package top.tcyeee.bookmarkify.entity.response

import cn.hutool.core.bean.BeanUtil
import cn.hutool.core.util.EnumUtil
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.enums.FunctionType
import top.tcyeee.bookmarkify.entity.enums.HomeItemType
import top.tcyeee.bookmarkify.entity.json.BookmarkDir
import top.tcyeee.bookmarkify.entity.entity.HomeItem

/**
 * 桌面排列中单个item,可能是是书签/书签文件夹/功能入口
 *
 * @author tcyeee
 * @date 4/17/24 12:57
 */
data class HomeItemShow(
    var id: String,
    var uid: String,

    @Schema(description = "序号") var sort: Int = 99,
    @Schema(description = "书签类型") var type: HomeItemType = HomeItemType.BOOKMARK,

    var bookmarkId: String? = null,     // 用于新建书签时定位
    var typeApp: BookmarkShow? = null,  // 书签信息
    var typeDir: BookmarkDir? = null,   // 书签组信息
    var typeFuc: FunctionType? = null,  // 系统功能入口
) {

    constructor(item: HomeItem, database: Map<String, BookmarkShow>, imgPrefix: String) : this(
        id = item.id, uid = item.uid
    ) {
        BeanUtil.copyProperties(item, this)
        when (item.type) {
            HomeItemType.BOOKMARK_DIR -> this.typeDir = BookmarkDir(database, item.bookmarkDirJson)
            HomeItemType.BOOKMARK -> this.typeApp = database[item.bookmarkUserLinkId]?.clean(imgPrefix)
            HomeItemType.FUNCTION -> this.typeFuc = EnumUtil.getEnumAt(FunctionType::class.java, item.functionId ?: 0)
        }
    }

    constructor(id: String, uid: String, bookmarkId: String) : this(
        id, uid, bookmarkId = bookmarkId, type = HomeItemType.BOOKMARK
    )
}