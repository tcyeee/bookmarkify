package top.tcyeee.bookmarkify.entity.response

import top.tcyeee.bookmarkify.entity.dto.BookmarkUrl
import top.tcyeee.bookmarkify.entity.entity.Bookmark

/**
 * @author tcyeee
 * @date 3/10/24 20:08
 */
data class BookmarkDetail(
    var bookmark: Bookmark,   // 公共书签
    var paths: List<String>,  // 路径  eg: /公共书签/娱乐/bilibili.com
    var url: BookmarkUrl,     // 用于数据清洗
    var bookmarkUserLinkId: String // 用户关联ID
) {
    constructor(paths: List<String>, url: BookmarkUrl, addDate: String, name: String) : this(
        bookmarkUserLinkId = "",
        bookmark = Bookmark(url, addDate, name),
        paths = paths,
        url = url
    )
}