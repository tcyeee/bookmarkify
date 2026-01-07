package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.mapper.HomeItemMapper
import top.tcyeee.bookmarkify.server.IHomeItemService
import top.tcyeee.bookmarkify.utils.SystemBookmarkStructure

/**
 * @author tcyeee
 * @date 4/14/24 15:38
 */
@Service
class HomeItemServiceImpl : IHomeItemService, ServiceImpl<HomeItemMapper, HomeItem>() {

    override fun chromeBookmarksPackage(structures: List<SystemBookmarkStructure>, uid: String): List<HomeItemShow> {
        val placeholders = mutableListOf<HomeItemShow>()
//
//        structures.forEach { folder ->
//            folder.bookmarks.forEach { row ->
//                val urlWrapper = runCatching { WebsiteParser.urlWrapper(row.url) }.getOrNull() ?: return@forEach
//                val bookmark = findByHost(urlWrapper.urlHost) ?: BookmarkEntity(urlWrapper).also { bookmarkMapper.insert(it) }
//
//                val userLink = BookmarkUserLink(urlWrapper, uid, bookmark).apply {
//                    title = row.title.takeIf { it.isNotBlank() } ?: title
//                }.also { bookmarkUserLinkMapper.insert(it) }
//                val homeItem = HomeItem(uid, userLink.id).also { homeItemMapper.insert(it) }
//
//                val bookmarkShow = BookmarkShow(
//                    bookmarkId = bookmark.id,
//                    bookmarkUserLinkId = userLink.id,
//                    title = row.title.takeIf { it.isNotBlank() } ?: bookmark.title,
//                    description = bookmark.description,
//                    urlFull = urlWrapper.urlRaw,
//                    urlBase = urlWrapper.urlFull,
//                    iconBase64 = row.iconBase64,
//                    isActivity = bookmark.isActivity,
//                    createTime = row.createTime?.toEpochSecond(ZoneOffset.UTC),
//                    paths = row.paths.takeIf { it.isNotBlank() }?.split("/"),
//                    uid = uid,
//                    hdSize = bookmark.maximalLogoSize,
//                    urlHost = urlWrapper.urlHost,
//                    appName = bookmark.appName,
//                )
//
//                placeholders.add(HomeItemShow(uid, homeItem.id, bookmarkShow))
//
//                if (bookmark.checkFlag) {
//                    kafkaMessageService.bookmarkParseAndNotice(uid, bookmark, userLink.id, homeItem.id)
//                }
//            }
//        }

        return placeholders
    }

    override fun delete(params: List<String>) =
        params.forEach { ktUpdate().set(HomeItem::deleted, true).eq(HomeItem::id, it).update() }

    override fun deleteOne(id: String): Boolean = ktUpdate().eq(HomeItem::id, id).set(HomeItem::deleted, true).update()

    @Transactional(rollbackFor = [Exception::class])
    override fun copy(sourceUid: String, targetUid: String): List<HomeItem> =
        ktQuery().eq(HomeItem::uid, sourceUid).eq(HomeItem::deleted, java.lang.Boolean.FALSE).list()
            .apply { this.forEach { it.uid = targetUid } }.also { saveBatch(it) }

//    private fun getByUid(uid: String): List<HomeItem> =
//        ktQuery().eq(HomeItem::uid, uid).eq(HomeItem::deleted, false).orderByAsc(HomeItem::sort).list() ?: emptyList()
}
