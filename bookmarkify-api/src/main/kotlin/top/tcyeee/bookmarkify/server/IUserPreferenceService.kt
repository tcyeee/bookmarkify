package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.UserPreferenceUpdateParams
import top.tcyeee.bookmarkify.entity.entity.UserPreferenceEntity

interface IUserPreferenceService : IService<UserPreferenceEntity> {
    fun queryByUid(uid: String): UserPreferenceEntity?
    fun upsertByUid(uid: String, params: UserPreferenceUpdateParams): Boolean
}

