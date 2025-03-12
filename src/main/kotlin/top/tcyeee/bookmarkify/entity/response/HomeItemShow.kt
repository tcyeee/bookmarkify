package top.tcyeee.bookmarkify.entity.response

import cn.hutool.core.bean.BeanUtil
import cn.hutool.core.util.EnumUtil
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.enums.HomeFunctionEnum
import top.tcyeee.bookmarkify.entity.enums.HomeItemTypeEnum
import top.tcyeee.bookmarkify.entity.json.BookmarkDir
import top.tcyeee.bookmarkify.entity.po.HomeItem

/**
 * @author tcyeee
 * @date 4/17/24 12:57
 */
data class HomeItemShow(
    var id: String,
    var uid: String,

    @Schema(description = "序号") var sort: Int = 99,
    @Schema(description = "书签类型,分别对应 typeFuc/typeDir/typeApp") var type: HomeItemTypeEnum = HomeItemTypeEnum.BOOKMARK,

    var typeApp: BookmarkShow? = null, // 书签信息
    var typeDir: BookmarkDir? = null, // 书签组信息
    var typeFuc: HomeFunctionEnum? = null, // 系统功能入口
) {

    constructor(item: HomeItem, database: Map<String, BookmarkShow>, imgPrefix: String) : this(
        id = "",
        uid = ""
    ) {
        BeanUtil.copyProperties(item, this)
        when (item.type) {
            HomeItemTypeEnum.BOOKMARK_DIR -> this.typeDir = BookmarkDir(database, item.bookmarkDirJson)
            HomeItemTypeEnum.BOOKMARK -> this.typeApp = database[item.bookmarkUserLinkId]?.init(imgPrefix)
            HomeItemTypeEnum.FUNCTION -> this.typeFuc =
                EnumUtil.getEnumAt(HomeFunctionEnum::class.java, item.functionId ?: 0)
        }
    }
}