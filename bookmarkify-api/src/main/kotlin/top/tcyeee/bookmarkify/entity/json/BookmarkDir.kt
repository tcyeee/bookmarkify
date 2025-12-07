package top.tcyeee.bookmarkify.entity.json

import cn.hutool.core.bean.BeanUtil
import cn.hutool.json.JSONUtil
import top.tcyeee.bookmarkify.entity.BookmarkShow
import java.util.function.Consumer

/**
 * @author tcyeee
 * @date 4/14/24 16:11
 */
data class BookmarkDir(
    var name: String,
    var bookmarkUserLinkIds: List<String>? = null, // BookmarkUserIds, 用于存储
    var bookmarkList: List<BookmarkShow>? = null, // 详细书签信息,用于展示
) {

    constructor(name: String, bookmarkIds: List<String>) : this(
        name = name,
        bookmarkUserLinkIds = bookmarkIds
    )

    constructor(database: Map<String, BookmarkShow>, json: String?) : this(
        name = "",
        bookmarkUserLinkIds = null
    ) {
        val bean = JSONUtil.toBean(json, BookmarkDir::class.java)
        BeanUtil.copyProperties(bean, this)
        val bookmarks: MutableList<BookmarkShow> = ArrayList()
        bean.bookmarkUserLinkIds?.forEach(Consumer { id: String? -> database[id]?.let { bookmarks.add(it) } })
        this.bookmarkList = bookmarks
    }
}