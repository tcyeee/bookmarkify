package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.UserBehaviorLogSearchParams
import top.tcyeee.bookmarkify.entity.UserBehaviorLogVO
import top.tcyeee.bookmarkify.entity.entity.UserBehaviorLogEntity
import top.tcyeee.bookmarkify.entity.enums.UserBehaviorType
import top.tcyeee.bookmarkify.mapper.UserBehaviorLogMapper
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IUserBehaviorLogService

/**
 * @author tcyeee
 */
@Service
class UserBehaviorLogServiceImpl(
    private val userMapper: UserMapper,
) : IUserBehaviorLogService, ServiceImpl<UserBehaviorLogMapper, UserBehaviorLogEntity>() {

    // ServiceImpl 自带的 log 是 MyBatis 的 Log 接口、没有 warn(String) 重载，同 ScrapperCallLogServiceImpl 的做法
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun record(uid: String, type: UserBehaviorType, detail: String?) {
        runCatching {
            val nickName = userMapper.selectById(uid)?.nickName
            save(UserBehaviorLogEntity(uid = uid, nickNameSnapshot = nickName, behaviorType = type, detail = detail))
        }.onFailure { logger.warn("[UserBehaviorLog] 记录用户行为失败 uid={} type={}: {}", uid, type, it.message) }
    }

    override fun adminListAll(params: UserBehaviorLogSearchParams): IPage<UserBehaviorLogVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { UserBehaviorLogVO(it) }
}
