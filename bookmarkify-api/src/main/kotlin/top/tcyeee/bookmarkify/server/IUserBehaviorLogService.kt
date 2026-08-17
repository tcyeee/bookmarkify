package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.UserBehaviorLogSearchParams
import top.tcyeee.bookmarkify.entity.UserBehaviorLogVO
import top.tcyeee.bookmarkify.entity.entity.UserBehaviorLogEntity
import top.tcyeee.bookmarkify.entity.enums.UserBehaviorType

/**
 * 用户行为审计日志 Service。
 */
interface IUserBehaviorLogService : IService<UserBehaviorLogEntity> {
    /**
     * 记一条用户行为。**永不抛出** —— 审计失败不应该影响被记录的那个业务操作本身
     * (同 [AccessTokenServiceImpl][top.tcyeee.bookmarkify.server.impl.AccessTokenServiceImpl]
     * 更新 lastUsedAt 的原则)。
     */
    fun record(uid: String, type: UserBehaviorType, detail: String? = null)

    fun adminListAll(params: UserBehaviorLogSearchParams): IPage<UserBehaviorLogVO>
}
