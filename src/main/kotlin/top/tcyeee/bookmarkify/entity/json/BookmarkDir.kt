package top.tcyeee.bookmarkify.entity.json

import top.tcyeee.bookmarkify.entity.response.BookmarkShow

/**
 * @author tcyeee
 * @date 4/14/24 16:11
 */
data class BookmarkDir(
    var name: String,
    var bookmarkUserLinkIds: List<String>, // BookmarkUserIds, 用于存储
    var bookmarkList: List<BookmarkShow?>, // 详细书签信息,用于展示
)