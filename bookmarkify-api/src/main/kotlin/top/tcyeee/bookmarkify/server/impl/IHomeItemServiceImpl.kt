package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.HomeItemSortParams
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.entity.enums.FileType
import top.tcyeee.bookmarkify.entity.enums.HomeItemType
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IHomeItemService
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * @author tcyeee
 * @date 4/14/24 15:38
 */
@Service
class IHomeItemServiceImpl(private val bookmarkUserLinkMapper: BookmarkUserLinkMapper) : IHomeItemService,
    ServiceImpl<HomeItemMapper, HomeItem>() {

    override fun findShowByUid(uid: String): List<HomeItemShow> {
        val dataMap = bookmarkUserLinkMapper.allBookmarkByUid(uid).associateBy {
            it.bookmarkUserLinkId.toString()
        }
        return this.getByUid(uid).map { HomeItemShow(it, dataMap) }.also { this.setIconHd(it) }
    }

    private fun setIconHd(list: List<HomeItemShow>) {
        list.forEach {
            if (it.type != HomeItemType.BOOKMARK) return@forEach
            val objectKey = "${FileType.WEBSITE_LOGO.folder}/${it.bookmarkId}/${it.typeApp?.hdSize}.png"
            it.typeApp?.iconHdUrl = OssUtils.getPrivateUrl(objectKey)
        }
    }

    private fun getByUid(uid: String): List<HomeItem> =
        ktQuery().eq(HomeItem::uid, uid).eq(HomeItem::deleted, false).orderByAsc(HomeItem::sort).list() ?: emptyList()

    override fun sort(params: List<HomeItemSortParams>) = params.forEach {
        ktUpdate().set(HomeItem::sort, it.sort).eq(HomeItem::id, it.id).update()
    }

    override fun delete(params: List<String>) =
        params.forEach { ktUpdate().set(HomeItem::deleted, true).eq(HomeItem::id, it).update() }

    override fun deleteOne(id: String): Boolean = ktUpdate().eq(HomeItem::id, id).set(HomeItem::deleted, true).update()

    @Transactional(rollbackFor = [Exception::class])
    override fun copy(sourceUid: String, targetUid: String): List<HomeItem> =
        ktQuery().eq(HomeItem::uid, sourceUid).eq(HomeItem::deleted, java.lang.Boolean.FALSE).list()
            .apply { this.forEach { it.uid = targetUid } }.also { saveBatch(it) }
}