package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.UserPreferenceEntity
import top.tcyeee.bookmarkify.mapper.UserPreferenceMapper
import top.tcyeee.bookmarkify.server.IUserPreferenceService

@Service
class UserPreferenceServiceImpl :
    IUserPreferenceService,
    ServiceImpl<UserPreferenceMapper, UserPreferenceEntity>() {

    override fun queryByUid(uid: String): UserPreferenceEntity? =
        ktQuery().eq(UserPreferenceEntity::uid, uid).one()

    override fun upsertByUid(uid: String, params: UserPreferenceEntity): Boolean {
        val entity = queryByUid(uid)?.apply { upsert(params) } ?: params.copy(uid = uid)
        return saveOrUpdate(entity)
    }
}

