package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.LayoutNodeSort
import top.tcyeee.bookmarkify.entity.UserPreferenceUpdateParams
import top.tcyeee.bookmarkify.entity.UserPreferenceVO
import top.tcyeee.bookmarkify.entity.entity.UserPreferenceEntity

interface IUserPreferenceService : IService<UserPreferenceEntity> {
    fun queryByUid(uid: String): UserPreferenceEntity
    fun queryShowByUid(uid: String): UserPreferenceVO
    fun upsertByUid(uid: String, params: UserPreferenceUpdateParams): Boolean
    fun sort(uid: String, params: List<LayoutNodeSort>): Boolean
}

