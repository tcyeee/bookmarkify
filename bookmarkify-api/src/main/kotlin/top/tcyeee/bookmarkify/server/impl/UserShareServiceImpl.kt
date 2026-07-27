package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.ShareCreateParams
import top.tcyeee.bookmarkify.entity.ShareSearchParams
import top.tcyeee.bookmarkify.entity.SharePublicVO
import top.tcyeee.bookmarkify.entity.ShareSharerVO
import top.tcyeee.bookmarkify.entity.UserShareAdminVO
import top.tcyeee.bookmarkify.entity.UserShareVO
import top.tcyeee.bookmarkify.entity.entity.UserShareBookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.UserShareEntity
import top.tcyeee.bookmarkify.entity.enums.ShareStatus
import top.tcyeee.bookmarkify.mapper.UserShareBookmarkMapper
import top.tcyeee.bookmarkify.mapper.UserShareMapper
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.server.IUserShareService

/**
 * @author tcyeee
 */
@Service
class UserShareServiceImpl(
    private val userShareBookmarkMapper: UserShareBookmarkMapper,
    private val userService: IUserService,
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
            userShareBookmarkMapper.insert(UserShareBookmarkEntity(shareId = share.id, bookmarkUserLinkId = linkId, sort = index))
        }
        return UserShareVO(share, params.bookmarkUserLinkIds.size)
    }

    override fun viewByCode(id: String): SharePublicVO {
        val share = getById(id) ?: throw CommonException(ErrorType.E122)
        if (share.effectiveStatus != ShareStatus.NORMAL) throw CommonException(ErrorType.E122)
        val user = userService.getById(share.uid) ?: throw CommonException(ErrorType.E122)
        val bookmarks = userShareBookmarkMapper.bookmarksByShareId(id).onEach { it.initLogo() }
        return SharePublicVO(
            id = share.id,
            note = share.note,
            expireTime = share.expireTime,
            status = share.effectiveStatus,
            sharer = ShareSharerVO(nickName = user.nickName, avatarUrl = userService.avatarSignedUrl(share.uid)),
            bookmarks = bookmarks,
        )
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

    override fun adminTakeDown(id: String): Boolean =
        ktUpdate().eq(UserShareEntity::id, id).set(UserShareEntity::status, ShareStatus.ADMIN_TAKEDOWN).update()
}
