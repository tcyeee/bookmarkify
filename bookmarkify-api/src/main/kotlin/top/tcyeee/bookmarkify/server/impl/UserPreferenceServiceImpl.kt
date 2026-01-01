package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.UserPreferenceEntity
import top.tcyeee.bookmarkify.mapper.UserPreferenceMapper
import top.tcyeee.bookmarkify.server.IUserPreferenceService
import java.time.LocalDateTime

@Service
class UserPreferenceServiceImpl :
    IUserPreferenceService,
    ServiceImpl<UserPreferenceMapper, UserPreferenceEntity>() {

    override fun queryByUid(uid: String): UserPreferenceEntity? =
        ktQuery().eq(UserPreferenceEntity::uid, uid).one()

    override fun upsertByUid(uid: String, params: UserPreferenceEntity): Boolean {
        val exists = queryByUid(uid)
        val now = LocalDateTime.now()
        val entity = exists?.apply {
            backgroundConfigId = params.backgroundConfigId
            bookmarkOpenMode = params.bookmarkOpenMode
            minimalMode = params.minimalMode
            bookmarkLayout = params.bookmarkLayout
            showTitle = params.showTitle
            pageMode = params.pageMode
            updateTime = now
        } ?: params.copy(uid = uid, updateTime = now, createTime = now)

        return saveOrUpdate(entity)
    }
}

