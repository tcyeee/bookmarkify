package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.BacSettingVO
import top.tcyeee.bookmarkify.entity.UserFileVO
import top.tcyeee.bookmarkify.entity.entity.BackgroundConfigEntity
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
import top.tcyeee.bookmarkify.entity.entity.BackgroundType
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
    private val backgroundGradientService: BackgroundGradientServiceImpl,
    private val backgroundImageService: BackgroundImageServiceImpl
) : IBackgroundConfigService, ServiceImpl<BackgroundConfigMapper, BackgroundConfigEntity>() {

    override fun queryByUid(uid: String): BackgroundConfigEntity =
        ktQuery().eq(BackgroundConfigEntity::uid, uid).one() ?: initUserBacSetting(uid)

    override fun deleteByUid(uid: String): Boolean = ktUpdate().eq(BackgroundConfigEntity::uid, uid).remove()

    override fun queryShowByUid(uid: String): BacSettingVO {
        val result = queryByUid(uid).vo()

        if (result.type == BackgroundType.GRADIENT) {
            backgroundGradientService.getById(result.backgroundLinkId)?.apply {
                result.bacColorGradient = this.gradientArray()
                result.bacColorDirection = this.direction
            }
        }

        if (result.type == BackgroundType.IMAGE) {
            result.bacImgFile = UserFileVO(
                id = result.backgroundLinkId,
                fullName = backgroundImageService.currentBacImgUrl(uid, result.backgroundLinkId)
            )
        }

        return result
    }

    private fun initUserBacSetting(uid: String): BackgroundConfigEntity {
        // 默认找到第一个默认渐变色进行关联
        val first =
            backgroundGradientService.ktQuery().eq(BackgroundGradientEntity::isDefault, true).last("limit 1").one()

        return BackgroundConfigEntity(
            uid = uid, type = BackgroundType.GRADIENT, backgroundLinkId = first.id
        ).also { save(it) }
    }
}
