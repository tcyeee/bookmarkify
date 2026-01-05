package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.bean.BeanUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.UserPreferenceUpdateParams
import top.tcyeee.bookmarkify.entity.UserPreferenceVO
import top.tcyeee.bookmarkify.entity.entity.UserPreferenceEntity
import top.tcyeee.bookmarkify.mapper.UserPreferenceMapper
import top.tcyeee.bookmarkify.server.IBackgroundConfigService
import top.tcyeee.bookmarkify.server.IUserPreferenceService

@Service
class UserPreferenceServiceImpl(
    private val backgroundConfigService: IBackgroundConfigService
) : IUserPreferenceService, ServiceImpl<UserPreferenceMapper, UserPreferenceEntity>() {

    override fun queryByUid(uid: String): UserPreferenceEntity =
        ktQuery().eq(UserPreferenceEntity::uid, uid).one() ?: UserPreferenceEntity(uid = uid).also { save(it) }

    override fun queryShowByUid(uid: String): UserPreferenceVO = queryByUid(uid).let { UserPreferenceVO(it) }.apply {
        imgBacShow = backgroundConfigService.queryShowByUid(uid)
    }

    override fun upsertByUid(uid: String, params: UserPreferenceUpdateParams): Boolean =
        queryByUid(uid).also { BeanUtil.copyProperties(params, it) }.let { saveOrUpdate(it) }
}

