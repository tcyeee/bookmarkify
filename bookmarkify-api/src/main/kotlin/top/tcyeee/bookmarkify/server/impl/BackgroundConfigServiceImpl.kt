package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.BacSettingVO
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
import top.tcyeee.bookmarkify.entity.entity.BackgroundType
import top.tcyeee.bookmarkify.entity.entity.BackgroundConfigEntity
import top.tcyeee.bookmarkify.mapper.BackgroundConfigMapper
import top.tcyeee.bookmarkify.server.IBackgroundConfigService

/**
 * 用户背景关联 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Service
class BackgroundConfigServiceImpl(
    private val backgroundGradientService: BackgroundGradientServiceImpl
) : IBackgroundConfigService,
    ServiceImpl<BackgroundConfigMapper, BackgroundConfigEntity>() {

    override fun queryByUid(uid: String): BackgroundConfigEntity =
        ktQuery().eq(BackgroundConfigEntity::uid, uid).one() ?: initUserBacSetting(uid)

    override fun deleteByUid(uid: String): Boolean = ktUpdate()
        .eq(BackgroundConfigEntity::uid, uid)
        .remove()

    override fun queryShowByUid(uid: String): BacSettingVO {
        TODO("Not yet implemented")
    }

    private fun initUserBacSetting(uid: String): BackgroundConfigEntity {
        // 默认找到第一个默认渐变色进行关联
        val first = backgroundGradientService.ktQuery()
            .eq(BackgroundGradientEntity::isDefault, true).last("limit 1").one()

        return BackgroundConfigEntity(
            uid = uid,
            type = BackgroundType.GRADIENT,
            backgroundLinkId = first.id
        ).also { save(it) }
    }
}
