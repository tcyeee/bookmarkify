package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
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
class UserBackgroundLinkServiceImpl(
    private val backgroundGradientService: BackgroundGradientServiceImpl
) : IUserBackgroundLinkService,
    ServiceImpl<UserBackgroundLinkMapper, UserBackgroundLinkEntity>() {

    override fun queryByUid(uid: String): UserBackgroundLinkEntity =
        ktQuery().eq(UserBackgroundLinkEntity::uid, uid).one() ?: initUserBacSetting(uid)

    private fun initUserBacSetting(uid: String): UserBackgroundLinkEntity {
        // 默认找到第一个默认渐变色进行关联
        val first = backgroundGradientService.ktQuery()
            .eq(BackgroundGradientEntity::isDefault, true).last("limit 1").one()

        return UserBackgroundLinkEntity(
            uid = uid,
            type = BackgroundType.GRADIENT,
            backgroundLinkId = first.id
        ).also { save(it) }
    }
}
