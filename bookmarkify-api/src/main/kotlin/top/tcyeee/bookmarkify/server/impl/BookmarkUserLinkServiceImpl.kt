package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.entity.BookmarkUpdatePrams
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.utils.WebsiteParser

/**
 * @author tcyeee
 * @date 3/10/24 22:18
 */
@Service
class BookmarkUserLinkServiceImpl : IBookmarkUserLinkService, ServiceImpl<BookmarkMapper, BookmarkEntity>() {
    override fun updateOne(params: BookmarkUpdatePrams, uid: String): Boolean =
        ktUpdate().eq(BookmarkEntity::id, params.linkId)
            .eq(BookmarkEntity::uid, uid)
            .set(BookmarkEntity::title, params.title)
            .set(BookmarkEntity::description, params.description)
            .update()

    override fun setPinned(linkId: String, pinned: Boolean, uid: String): Boolean =
        ktUpdate().eq(BookmarkEntity::id, linkId)
            .eq(BookmarkEntity::uid, uid)
            .set(BookmarkEntity::pinned, pinned)
            .update()

    // 用 setSql 做原子自增，避免"读旧值→+1→回写"在并发打开时互相覆盖丢计数
    override fun recordOpen(linkId: String, uid: String): Boolean =
        ktUpdate().eq(BookmarkEntity::id, linkId)
            .eq(BookmarkEntity::uid, uid)
            .setSql("open_count = open_count + 1")
            .update()

    @Transactional(rollbackFor = [Exception::class])
    override fun copy(sourceUid: String, targetUid: String) {
        // F-06 (dead-code warning): This method has no current callers and MUST NOT be activated
        // without first copying the UserLayoutNodeEntity rows for targetUid and providing a
        // nodeIdMap (oldNodeId -> newNodeId) to remap layoutNodeId. Without that remapping,
        // every copied BookmarkEntity row will reference a layout node owned by sourceUid;
        // layout() looks up bookmark data by layoutNodeId and will silently return null for all
        // of them, making the copied bookmarks invisible on targetUid's desktop.
        val source: List<BookmarkEntity> =
            ktQuery().eq(BookmarkEntity::uid, sourceUid).eq(BookmarkEntity::deleted, java.lang.Boolean.FALSE).list()
        // 用 data class copy 生成全新主键的副本，避免 saveBatch 用源主键触发冲突或误更新
        val copies = source.map { it.copy(id = cn.hutool.core.util.IdUtil.fastUUID(), uid = targetUid) }
        this.saveBatch(copies)
    }

    override fun deleteOneByNodeId(layoutNodeId: String, uid: String) {
        ktUpdate()
            .eq(BookmarkEntity::layoutNodeId, layoutNodeId)
            .eq(BookmarkEntity::uid, uid)
            .remove()
    }

    override fun resetPageId(uid: String, userLinkId: String, pageId: String): Boolean =
        ktUpdate()
            .eq(BookmarkEntity::uid, uid)
            .eq(BookmarkEntity::id, userLinkId)
            .set(BookmarkEntity::pageId, pageId)
            .update()

    // set(null) 而不是 setSql("page_id = null")：MyBatis-Plus 的 KtUpdateWrapper 对
    // 显式传入的 null 会照常拼进 SET 子句（区别于实体更新时的"null 字段跳过"）
    override fun clearUnboundMarker(userLinkId: String): Boolean =
        ktUpdate()
            .eq(BookmarkEntity::id, userLinkId)
            .set(BookmarkEntity::pageId, null)
            .update()

    override fun urlsByUid(uid: String): Set<String> =
        ktQuery()
            .eq(BookmarkEntity::uid, uid)
            .eq(BookmarkEntity::deleted, false)
            .list()
            .map { it.urlFull }
            .toHashSet()

    override fun bookmarkIdsByUid(uid: String): Set<String> =
        ktQuery()
            .eq(BookmarkEntity::uid, uid)
            .eq(BookmarkEntity::deleted, false)
            .list()
            .mapNotNull { it.pageId }
            .toHashSet()

    override fun duplicatePageIds(uid: String): Set<String> =
        ktQuery()
            .eq(BookmarkEntity::uid, uid)
            .eq(BookmarkEntity::deleted, false)
            .list()
            .mapNotNull { it.pageId }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

    @Transactional(rollbackFor = [Exception::class])
    override fun reclassifyAllLinkTypes(): Int {
        val all = ktQuery().eq(BookmarkEntity::deleted, false).list()
        // 按重新计算出的类型分组，每组一条 UPDATE ... WHERE id IN (...)，避免逐条更新
        all.groupBy { link ->
            runCatching { WebsiteParser.classifyLinkType(WebsiteParser.urlWrapper(link.urlFull).urlHost) }
                .getOrDefault(BookmarkLinkType.OTHER)
        }.forEach { (type, links) ->
            ktUpdate().`in`(BookmarkEntity::id, links.map { it.id }).set(BookmarkEntity::linkType, type).update()
        }
        return all.size
    }
}