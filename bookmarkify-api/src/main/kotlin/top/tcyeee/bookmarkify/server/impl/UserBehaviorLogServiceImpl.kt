package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.monitor.LogMarkers
import top.tcyeee.bookmarkify.entity.UserBehaviorLogSearchParams
import top.tcyeee.bookmarkify.entity.UserBehaviorLogVO
import top.tcyeee.bookmarkify.entity.entity.UserBehaviorLogEntity
import top.tcyeee.bookmarkify.entity.enums.UserBehaviorType
import top.tcyeee.bookmarkify.mapper.UserBehaviorLogMapper
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IUserBehaviorLogService
import top.tcyeee.bookmarkify.server.admin.AdminUserViewAssembler
import top.tcyeee.bookmarkify.server.log.LogRetention
import java.time.LocalDateTime

/**
 * @author tcyeee
 */
@Service
class UserBehaviorLogServiceImpl(
    private val userMapper: UserMapper,
    private val adminUserViewAssembler: AdminUserViewAssembler,
) : IUserBehaviorLogService, ServiceImpl<UserBehaviorLogMapper, UserBehaviorLogEntity>() {

    // ServiceImpl 自带的 log 是 MyBatis 的 Log 接口、没有 warn(String) 重载，同 ScrapperCallLogServiceImpl 的做法
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun record(uid: String, type: UserBehaviorType, detail: String?, nickName: String?) {
        runCatching {
            // 调用方手里已有用户实体时（登录链路）直接把昵称传进来，省掉这次反查
            val snapshot = nickName ?: userMapper.selectById(uid)?.nickName
            save(UserBehaviorLogEntity(uid = uid, nickNameSnapshot = snapshot, behaviorType = type, detail = detail))
        }.onFailure {
            // INFRA：审计写不进去 = 这段时间的用户行为无从追溯，与业务无关但必须有人知道
            logger.warn(LogMarkers.INFRA, "[UserBehaviorLog] 记录用户行为失败 uid={} type={}: {}", uid, type, it.message)
        }
    }

    override fun purgeExpired(): Int {
        val before = LocalDateTime.now().minusDays(LogRetention.USER_BEHAVIOR_DAYS)
        return baseMapper.delete(
            KtQueryWrapper(UserBehaviorLogEntity::class.java).lt(UserBehaviorLogEntity::createTime, before)
        )
    }

    override fun adminListAll(params: UserBehaviorLogSearchParams): IPage<UserBehaviorLogVO> {
        val page = baseMapper.selectPage(params.toPage(), params.toWrapper())
        val userByUid = adminUserViewAssembler.findByIds(page.records.map { it.uid }.distinct())
        return page.convert { UserBehaviorLogVO(it).apply { user = userByUid[it.uid] } }
    }
}
