package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IHomeItemService
import java.util.function.Consumer

/**
 * @author tcyeee
 * @date 4/14/24 15:38
 */
@Service
class IHomeItemServiceImpl(
    private val projectConfig: ProjectConfig,
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper
) : IHomeItemService, ServiceImpl<HomeItemMapper, HomeItem>() {

    override fun findShowByUid(uid: String): List<HomeItemShow> {
        val dataMap = createDataBaseByUid(uid)
        val byUid = findByUid(uid) ?: return emptyList()
        return byUid.map { item -> HomeItemShow(item, dataMap, projectConfig.imgPrefix) }
    }

    override fun createDataBaseByUid(uid: String): Map<String, BookmarkShow> {
        val bookmarks: List<BookmarkShow> = bookmarkUserLinkMapper.allBookmarkByUid(uid)
        return bookmarks.associateBy { it.bookmarkUserLinkId.toString() }
    }

    override fun findByUid(uid: String): List<HomeItem>? {
        val result: List<HomeItem> = ktQuery()
            .eq(HomeItem::uid, uid).eq(HomeItem::deleted, java.lang.Boolean.FALSE).orderByAsc(HomeItem::sort).list()
        return result.ifEmpty { null }
    }

    override fun sort(params: List<HomeItem>): Boolean {
        params.forEach(Consumer { item: HomeItem ->
            ktUpdate().set(HomeItem::sort, item.sort).eq(HomeItem::id, item.id).update()
        })
        return true
    }

    override fun delete(params: List<String>) =
        params.forEach { ktUpdate().set(HomeItem::deleted, true).eq(HomeItem::id, it).update() }

    override fun deleteOne(id: String): Boolean =
        ktUpdate().eq(HomeItem::id, id).set(HomeItem::deleted, true).update()

    @Transactional(rollbackFor = [Exception::class])
    override fun copy(sourceUid: String, targetUid: String) {
        val source: List<HomeItem> =
            ktQuery().eq(HomeItem::uid, sourceUid).eq(HomeItem::deleted, java.lang.Boolean.FALSE).list()
        source.forEach(Consumer { item: HomeItem -> item.uid = targetUid })
        this.saveBatch(source)
    }
}