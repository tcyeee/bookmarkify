package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.entity.BookmarkUpdatePrams
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService

/**
 * @author tcyeee
 * @date 3/10/24 22:18
 */
@Service
class BookmarkUserLinkServiceImpl : IBookmarkUserLinkService, ServiceImpl<BookmarkUserLinkMapper, BookmarkUserLink>() {
    override fun updateOne(params: BookmarkUpdatePrams, uid: String): Boolean =
        ktUpdate().eq(BookmarkUserLink::id, params.linkId)
            .eq(BookmarkUserLink::uid, uid)
            .set(BookmarkUserLink::title, params.title)
            .set(BookmarkUserLink::description, params.description)
            .update()

    @Transactional(rollbackFor = [Exception::class])
    override fun copy(sourceUid: String, targetUid: String) {
        val source: List<BookmarkUserLink> =
            ktQuery().eq(BookmarkUserLink::uid, sourceUid).eq(BookmarkUserLink::deleted, java.lang.Boolean.FALSE).list()
        // 用 data class copy 生成全新主键的副本，避免 saveBatch 用源主键触发冲突或误更新
        val copies = source.map { it.copy(id = cn.hutool.core.util.IdUtil.fastUUID(), uid = targetUid) }
        this.saveBatch(copies)
    }

    override fun deleteOneByNodeId(layoutNodeId: String, uid: String) {
        ktUpdate()
            .eq(BookmarkUserLink::layoutNodeId, layoutNodeId)
            .eq(BookmarkUserLink::uid, uid)
            .remove()
    }

    override fun resetBookmarkId(uid: String, userLinkId: String, bookmarkId: String): Boolean =
        ktUpdate()
            .eq(BookmarkUserLink::uid, uid)
            .eq(BookmarkUserLink::id, userLinkId)
            .set(BookmarkUserLink::bookmarkId, bookmarkId)
            .update()
}