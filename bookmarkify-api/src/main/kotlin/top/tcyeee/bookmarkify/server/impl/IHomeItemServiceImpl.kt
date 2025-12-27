package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.HomeItemSortParams
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IHomeItemService

/**
 * @author tcyeee
 * @date 4/14/24 15:38
 */
@Service
class IHomeItemServiceImpl(
    private val projectConfig: ProjectConfig, private val bookmarkUserLinkMapper: BookmarkUserLinkMapper
) : IHomeItemService, ServiceImpl<HomeItemMapper, HomeItem>() {

    override fun findShowByUid(uid: String): List<HomeItemShow> {
        val dataMap = bookmarkUserLinkMapper.allBookmarkByUid(uid).associateBy { it.bookmarkUserLinkId.toString() }

        val byUid = ktQuery().eq(HomeItem::uid, uid).eq(HomeItem::deleted, false).orderByAsc(HomeItem::sort).list()
            ?: return emptyList()

        return byUid.map { HomeItemShow(it, dataMap, projectConfig.imgPrefix) }
    }

    override fun sort(params: List<HomeItemSortParams>) = params.forEach {
        ktUpdate().set(HomeItem::sort, it.sort).eq(HomeItem::id, it.id).update()
    }

    override fun delete(params: List<String>) =
        params.forEach { ktUpdate().set(HomeItem::deleted, true).eq(HomeItem::id, it).update() }

    override fun deleteOne(id: String): Boolean = ktUpdate().eq(HomeItem::id, id).set(HomeItem::deleted, true).update()

    @Transactional(rollbackFor = [Exception::class])
    override fun copy(sourceUid: String, targetUid: String): List<HomeItem> =
        ktQuery().eq(HomeItem::uid, sourceUid).eq(HomeItem::deleted, java.lang.Boolean.FALSE).list()
            .apply { this.forEach { it.uid = targetUid } }
            .also { saveBatch(it) }

}