package top.tcyeee.bookmarkify.entity.response

import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.enums.HomeFunctionEnum
import top.tcyeee.bookmarkify.entity.enums.HomeItemTypeEnum
import top.tcyeee.bookmarkify.entity.json.BookmarkDir

/**
 * @author tcyeee
 * @date 4/17/24 12:57
 */
data class HomeItemShow(
    var id: String,
    var uid: String,

    @Schema(description = "序号") var sort: Int,
    @Schema(description = "书签类型,分别对应 typeFuc/typeDir/typeApp") var type: HomeItemTypeEnum,

    var typeApp: BookmarkShow, // 书签信息
    var typeDir: BookmarkDir, // 书签组信息
    var typeFuc: HomeFunctionEnum, // 系统功能入口

)