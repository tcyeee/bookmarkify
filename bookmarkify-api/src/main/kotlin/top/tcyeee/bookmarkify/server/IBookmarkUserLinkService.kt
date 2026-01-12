package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.BookmarkUpdatePrams
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink

/**
 * @author tcyeee
 * @date 3/10/24 22:18
 */
interface IBookmarkUserLinkService : IService<BookmarkUserLink> {
    fun updateOne(params: BookmarkUpdatePrams): Boolean
    fun copy(sourceUid: String, targetUid: String)
    fun deleteOne(id: String): Boolean
    /** 通过查询Host,将用户自定义书签和元书签关联上 */
    fun resetBookmarkId(uid: String, urlHost: String, bookmarkId: String): Boolean
}