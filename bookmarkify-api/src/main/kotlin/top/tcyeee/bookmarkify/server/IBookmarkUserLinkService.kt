package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.BookmarkUpdataPrams
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink

/**
 * @author tcyeee
 * @date 3/10/24 22:18
 */
interface IBookmarkUserLinkService : IService<BookmarkUserLink> {
    fun updateOne(params: BookmarkUpdataPrams): Boolean
    fun copy(sourceUid: String, targetUid: String)
    fun deleteOne(id: String): Boolean
}