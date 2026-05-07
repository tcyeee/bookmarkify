package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.BookmarkUpdatePrams
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink

/**
 * @author tcyeee
 * @date 3/10/24 22:18
 */
interface IBookmarkUserLinkService : IService<BookmarkUserLink> {
    /** 修改用户书签的标题/描述；仅修改属于该用户自己的记录 */
    fun updateOne(params: BookmarkUpdatePrams, uid: String): Boolean
    fun copy(sourceUid: String, targetUid: String)
    /** 按布局节点 ID 删除用户书签；仅删除属于该用户的记录 */
    fun deleteOneByNodeId(layoutNodeId: String, uid: String)
    /** 通过查询Host,将用户自定义书签和元书签关联上 */
    fun resetBookmarkId(uid: String, userLinkId: String, bookmarkId: String): Boolean
}