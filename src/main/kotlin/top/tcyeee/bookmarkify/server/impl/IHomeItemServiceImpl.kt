package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.context.annotation.Description
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.po.HomeItem
import top.tcyeee.bookmarkify.entity.response.BookmarkShow
import top.tcyeee.bookmarkify.entity.response.HomeItemShow
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
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
    private val bookmarkMapper: BookmarkMapper
) : IHomeItemService, ServiceImpl<HomeItemMapper, HomeItem>() {

    override fun findShowByUid(uid: String): List<HomeItemShow> {
        val dataMap = createDataBaseByUid(uid)
        val byUid = findByUid(uid) ?: return emptyList()
        return byUid.map { item -> HomeItemShow(item, dataMap, projectConfig.imgPath) }
    }

    override fun createDataBaseByUid(uid: String): Map<String, BookmarkShow> {
        val bookmarks: List<BookmarkShow> = bookmarkMapper.allBookmarkByUid(uid)
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

    override fun delete(params: List<HomeItem>): Boolean {
        params.forEach(Consumer { item: HomeItem ->
            ktUpdate().set(HomeItem::deleted, java.lang.Boolean.TRUE).eq(HomeItem::id, item.id).update()
        })
        return true
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun copy(sourceUid: String, targetUid: String) {
        val source: List<HomeItem> =
            ktQuery().eq(HomeItem::uid, sourceUid).eq(HomeItem::deleted, java.lang.Boolean.FALSE).list()
        source.forEach(Consumer { item: HomeItem -> item.uid = targetUid })
        this.saveBatch(source)
    }
}