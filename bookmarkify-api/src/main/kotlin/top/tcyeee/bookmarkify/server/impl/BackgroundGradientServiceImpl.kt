package top.tcyeee.bookmarkify.server.impl

import cn.hutool.json.JSONUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.cache.RedisCache
import top.tcyeee.bookmarkify.config.cache.RedisType
import top.tcyeee.bookmarkify.entity.BacGradientVO
import top.tcyeee.bookmarkify.entity.GradientConfigParams
import top.tcyeee.bookmarkify.entity.entity.BackgroundGradientEntity
import top.tcyeee.bookmarkify.mapper.BackgroundGradientMapper
import top.tcyeee.bookmarkify.server.IBackgroundGradientService

/**
 * 用户渐变背景 Service 实现
 *
 * @author tcyeee
 * @date 12/7/25 15:00
 */
@Service
class BackgroundGradientServiceImpl : IBackgroundGradientService,
    ServiceImpl<BackgroundGradientMapper, BackgroundGradientEntity>() {

    @RedisCache(RedisType.DEFAULT_BACKGROUND_GRADIENTS)
    override fun defaultGradientBackgrounds(): Array<BacGradientVO> =
        ktQuery().eq(BackgroundGradientEntity::isDefault, true).list()
            .map { it.vo() }.toTypedArray()

    override fun userGradientBackgrounds(uid: String): Array<BacGradientVO> =
        ktQuery().eq(BackgroundGradientEntity::uid, uid)
            .eq(BackgroundGradientEntity::isDefault, false)
            .list()
            .map { it.vo() }
            .toTypedArray()

    override fun deleteUserGradient(uid: String, id: String): Boolean =
        ktUpdate()
            .eq(BackgroundGradientEntity::uid, uid)
            .eq(BackgroundGradientEntity::id, id)
            .eq(BackgroundGradientEntity::isDefault, false)
            .remove()

    override fun updateUserGradient(uid: String, params: GradientConfigParams): Boolean =
         ktUpdate().eq(BackgroundGradientEntity::uid, uid)
            .eq(BackgroundGradientEntity::id, params.id)
            .eq(BackgroundGradientEntity::isDefault, false)
            .set(BackgroundGradientEntity::gradient, JSONUtil.toJsonStr(params.colors))
            .set(BackgroundGradientEntity::direction, params.direction)
            .update()
}
