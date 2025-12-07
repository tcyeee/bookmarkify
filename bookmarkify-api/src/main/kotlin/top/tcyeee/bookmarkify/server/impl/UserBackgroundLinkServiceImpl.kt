package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.BackgroundType
import top.tcyeee.bookmarkify.entity.entity.UserBackgroundLinkEntity
import top.tcyeee.bookmarkify.mapper.UserBackgroundLinkMapper
import top.tcyeee.bookmarkify.server.IUserBackgroundLinkService

/**
 * 用户背景关联 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Service
class UserBackgroundLinkServiceImpl : IUserBackgroundLinkService,
    ServiceImpl<UserBackgroundLinkMapper, UserBackgroundLinkEntity>() {

    override fun queryByUid(uid: String): UserBackgroundLinkEntity {
        val entity = ktQuery().eq(UserBackgroundLinkEntity::uid, uid).one()

        if (entity != null) return entity

        return UserBackgroundLinkEntity(
            uid = uid,
            type = BackgroundType.IMAGE,
            backgroundLinkId = ""
        ).also { save(it) }
    }
}
