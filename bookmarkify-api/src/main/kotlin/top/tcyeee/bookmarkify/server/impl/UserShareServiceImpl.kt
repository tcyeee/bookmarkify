package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.config.event.ShareAiReviewEvent
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.result.PageBean
import top.tcyeee.bookmarkify.entity.ShareCreateParams
import top.tcyeee.bookmarkify.entity.ShareSearchParams
import top.tcyeee.bookmarkify.entity.ShareAdminBookmarkVO
import top.tcyeee.bookmarkify.entity.ShareAdminDetailVO
import top.tcyeee.bookmarkify.entity.SharePublicVO
import top.tcyeee.bookmarkify.entity.ShareStatusChangedVO
import top.tcyeee.bookmarkify.entity.ShareSharerVO
import top.tcyeee.bookmarkify.entity.ShareUpdateParams
import top.tcyeee.bookmarkify.entity.UserShareAdminVO
import top.tcyeee.bookmarkify.entity.UserShareVO
import top.tcyeee.bookmarkify.entity.dto.AiReviewOutcome
import top.tcyeee.bookmarkify.entity.entity.UserShareBookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.UserShareEntity
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.entity.enums.ShareStatus
import top.tcyeee.bookmarkify.mapper.UserShareBookmarkMapper
import top.tcyeee.bookmarkify.mapper.UserShareMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.server.IUserShareService
import top.tcyeee.bookmarkify.server.asset.IconResolver
import top.tcyeee.bookmarkify.utils.SocketUtils
import java.time.LocalDateTime

/**
 * @author tcyeee
 */
@Service
class UserShareServiceImpl(
    private val userShareBookmarkMapper: UserShareBookmarkMapper,
    private val userService: IUserService,
    private val apiService: IApiService,
    private val eventPublisher: ApplicationEventPublisher,
    private val iconResolver: IconResolver,
) : IUserShareService, ServiceImpl<UserShareMapper, UserShareEntity>() {

    @Transactional(rollbackFor = [Exception::class])
    override fun createShare(params: ShareCreateParams, uid: String): UserShareVO {
        val share = UserShareEntity(
            uid = uid,
            note = params.note?.trim()?.takeIf { it.isNotBlank() },
            expireTime = params.expireTime,
        )
        save(share)
        params.bookmarkUserLinkIds.forEachIndexed { index, linkId ->
            userShareBookmarkMapper.insert(UserShareBookmarkEntity(shareId = share.id, bookmarkId = linkId, sort = index))
        }
        applyRuleReview(share)
        return UserShareVO(share, params.bookmarkUserLinkIds.size)
    }

    /**
     * 规则审核(普通审核)：分享中的书签必须都是域名类型，且都不能是疑似违规(nsfw)书签。
     * 未通过则直接将分享状态落库为 REVIEW_REJECTED 并附上理由；通过则发布事件触发异步 AI 审核。
     */
    private fun applyRuleReview(share: UserShareEntity) {
        val reason = reviewRules(share.id)
        if (reason != null) {
            share.status = ShareStatus.REVIEW_REJECTED
            share.rejectReason = reason
            share.updateTime = LocalDateTime.now()
            updateById(share)
        } else {
            eventPublisher.publishEvent(ShareAiReviewEvent(share.id))
        }
    }

    private fun reviewRules(shareId: String): String? {
        val bookmarks = userShareBookmarkMapper.bookmarksByShareId(shareId)
        if (bookmarks.any { it.linkType != BookmarkLinkType.DOMAIN }) {
            return "分享中包含非域名类型的书签（本地/IP地址等），暂不支持分享"
        }
        if (bookmarks.any { it.nsfw }) {
            return "分享中包含疑似违规内容的书签，无法发布"
        }
        return null
    }

    override fun viewByCode(id: String): SharePublicVO {
        val share = getById(id) ?: throw CommonException(ErrorType.E122)
        if (share.effectiveStatus != ShareStatus.NORMAL) throw CommonException(ErrorType.E122)
        val user = userService.getById(share.uid) ?: throw CommonException(ErrorType.E122)
        // 分享页渲染的是 20px 的列表行（pages/share/[code].vue），按 LIST 解析。
        // 此前它走 TILE —— 256px 的签名图喂 20px 的格子，还会因 monogram 兜底把够用的小图换成色块
        val bookmarks = iconResolver.decorate(userShareBookmarkMapper.bookmarksByShareId(id), DisplayMode.LIST)
        return SharePublicVO(
            id = share.id,
            note = share.note,
            expireTime = share.expireTime,
            status = share.effectiveStatus,
            sharer = ShareSharerVO(nickName = user.nickName, avatarUrl = userService.avatarSignedUrl(share.uid)),
            bookmarks = bookmarks,
        )
    }

    override fun listMine(uid: String, pageBean: PageBean): IPage<UserShareVO> {
        val page = baseMapper.selectPage(
            pageBean.toPage(),
            KtQueryWrapper(UserShareEntity::class.java).eq(UserShareEntity::uid, uid).orderByDesc(UserShareEntity::createTime),
        )
        return page.convert { entity ->
            val count = userShareBookmarkMapper.selectCount(
                KtQueryWrapper(UserShareBookmarkEntity::class.java).eq(UserShareBookmarkEntity::shareId, entity.id)
            ).toInt()
            UserShareVO(entity, count)
        }
    }

    override fun cancelShare(id: String, uid: String): Boolean {
        val share = getById(id) ?: throw CommonException(ErrorType.E122)
        if (share.uid != uid) throw CommonException(ErrorType.E123)
        // 未通过审核的分享本身就未公开可见，允许用户自行撤下以清理列表
        if (share.effectiveStatus != ShareStatus.NORMAL && share.effectiveStatus != ShareStatus.REVIEW_REJECTED) {
            throw CommonException(ErrorType.E122)
        }
        return ktUpdate().eq(UserShareEntity::id, id)
            .set(UserShareEntity::status, ShareStatus.CANCELLED)
            .set(UserShareEntity::updateTime, LocalDateTime.now())
            .update()
    }

    @Transactional(rollbackFor = [Exception::class])
    override fun updateShare(params: ShareUpdateParams, uid: String): UserShareVO {
        val share = getById(params.id) ?: throw CommonException(ErrorType.E122)
        if (share.uid != uid) throw CommonException(ErrorType.E123)
        if (share.effectiveStatus != ShareStatus.NORMAL) throw CommonException(ErrorType.E122)
        share.note = params.note?.trim()?.takeIf { it.isNotBlank() }
        share.expireTime = params.expireTime
        share.updateTime = LocalDateTime.now()
        updateById(share)
        applyRuleReview(share)
        val count = userShareBookmarkMapper.selectCount(
            KtQueryWrapper(UserShareBookmarkEntity::class.java).eq(UserShareBookmarkEntity::shareId, share.id)
        ).toInt()
        return UserShareVO(share, count)
    }

    override fun performAiReview(shareId: String) {
        val share = getById(shareId) ?: return
        if (share.effectiveStatus != ShareStatus.NORMAL) return
        val bookmarks = userShareBookmarkMapper.bookmarksByShareId(shareId)
        val rejectReason = bookmarks.firstNotNullOfOrNull { bookmark ->
            when (val outcome = apiService.inferContentViolation(bookmark.title, bookmark.description, bookmark.urlHost ?: "")) {
                is AiReviewOutcome.Rejected -> outcome.reason
                AiReviewOutcome.Unavailable -> "AI 审核暂时不可用，请稍后重试"
                AiReviewOutcome.Pass -> null
            }
        } ?: return

        ktUpdate().eq(UserShareEntity::id, shareId)
            .set(UserShareEntity::status, ShareStatus.REVIEW_REJECTED)
            .set(UserShareEntity::rejectReason, rejectReason)
            .set(UserShareEntity::updateTime, LocalDateTime.now())
            .update()
        SocketUtils.shareStatusChanged(share.uid, ShareStatusChangedVO(shareId, ShareStatus.REVIEW_REJECTED, rejectReason))
    }

    override fun adminListAll(params: ShareSearchParams): IPage<UserShareAdminVO> {
        val page = baseMapper.selectPage(params.toPage(), params.toWrapper())
        val uids = page.records.map { it.uid }.distinct()
        val nickNameByUid = if (uids.isEmpty()) emptyMap() else userService.listByIds(uids).associate { it.id to it.nickName }
        return page.convert { entity ->
            val count = userShareBookmarkMapper.selectCount(
                KtQueryWrapper(UserShareBookmarkEntity::class.java).eq(UserShareBookmarkEntity::shareId, entity.id)
            ).toInt()
            UserShareAdminVO(entity, nickNameByUid[entity.uid] ?: "-", count)
        }
    }

    /**
     * 管理端详情。与 [viewByCode] 的关键差别是**不校验状态**：后台要看的恰恰是被下架、
     * 未过审的那几条 —— 用公开视图去查，需要人工复核的分享一条也打不开。
     */
    override fun adminDetail(id: String): ShareAdminDetailVO? {
        val share = getById(id) ?: return null
        val nickName = userService.getById(share.uid)?.nickName ?: "-"
        // 与分享页同为列表形态，按 LIST 解析（后台详情弹窗里也是小图 + 全名）
        val bookmarks = iconResolver.decorate(userShareBookmarkMapper.bookmarksByShareId(id), DisplayMode.LIST)
        return ShareAdminDetailVO(
            share = UserShareAdminVO(share, nickName, bookmarks.size),
            bookmarks = bookmarks.map { ShareAdminBookmarkVO(it) },
        )
    }

    override fun adminTakeDown(id: String): Boolean =
        ktUpdate().eq(UserShareEntity::id, id).set(UserShareEntity::status, ShareStatus.ADMIN_TAKEDOWN).update()
}
