package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.entity.BookmarkUpdatePrams
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import java.util.function.Consumer

/**
 * @author tcyeee
 * @date 3/10/24 22:18
 */
@Service
class BookmarkUserLinkServiceImpl : IBookmarkUserLinkService, ServiceImpl<BookmarkUserLinkMapper, BookmarkUserLink>() {
    override fun updateOne(params: BookmarkUpdatePrams): Boolean {
        ktUpdate().eq(BookmarkUserLink::id, params.linkId).set(BookmarkUserLink::title, params.title)
            .set(BookmarkUserLink::description, params.description).update()
        return false
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun copy(sourceUid: String, targetUid: String) {
        val source: List<BookmarkUserLink> =
            ktQuery().eq(BookmarkUserLink::uid, sourceUid).eq(BookmarkUserLink::deleted, java.lang.Boolean.FALSE).list()
        source.forEach(Consumer { item: BookmarkUserLink -> item.uid = targetUid })
        this.saveBatch(source)
    }

    override fun deleteOne(id: String): Boolean =
        ktUpdate().eq(BookmarkUserLink::id, id).set(BookmarkUserLink::deleted, true).update()
}