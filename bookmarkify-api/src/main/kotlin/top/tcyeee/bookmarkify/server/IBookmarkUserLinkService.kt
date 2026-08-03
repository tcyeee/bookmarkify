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
    /** 置顶/取消置顶用户书签；仅修改属于该用户自己的记录 */
    fun setPinned(linkId: String, pinned: Boolean, uid: String): Boolean
    /** 记录一次书签打开(仅做计数，不做展示)；仅修改属于该用户自己的记录 */
    fun recordOpen(linkId: String, uid: String): Boolean
    fun copy(sourceUid: String, targetUid: String)
    /** 按布局节点 ID 删除用户书签；仅删除属于该用户的记录 */
    fun deleteOneByNodeId(layoutNodeId: String, uid: String)
    /** 通过查询Host,将用户自定义书签和元书签关联上 */
    fun resetBookmarkId(uid: String, userLinkId: String, bookmarkId: String): Boolean

    /**
     * 把「等着被绑定」的占位标记(`bookmark_id = 'LOADING'`)清成 NULL，表示这条记录**确定**
     * 没有 canonical 书签，不必再等。
     *
     * 只在无源书签的终结路径上调用（见 BookmarkServiceImpl.finishNodeWithoutBookmark）。
     * 不清的话 assertNotPendingImport 会永远把它当成「还在导入队列里」，用户之后添加同一个
     * 网址会拿到一个假的 E126。
     */
    fun clearUnboundMarker(userLinkId: String): Boolean
    /** 返回用户所有未删除书签的完整 URL 集合，用于导入时重复检测 */
    fun urlsByUid(uid: String): Set<String>
    /** 返回用户所有未删除书签关联的 bookmarkId 集合 */
    fun bookmarkIdsByUid(uid: String): Set<String>
    /** 返回用户被添加了多次的 bookmarkId 集合(同一站点收藏了 ≥2 次)，用于"重复书签"筛选 */
    fun duplicateBookmarkIds(uid: String): Set<String>
    /** 管理端：按 urlFull 重新计算全部书签的 linkType(域名/本地/IP/其他) 并批量回写，返回处理总数 */
    fun reclassifyAllLinkTypes(): Int
}