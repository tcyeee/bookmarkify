package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.monitor.LogMarkers
import top.tcyeee.bookmarkify.entity.AdminActionLogSearchParams
import top.tcyeee.bookmarkify.entity.entity.AdminActionLogEntity
import top.tcyeee.bookmarkify.mapper.AdminActionLogMapper
import top.tcyeee.bookmarkify.server.IAdminActionLogService
import top.tcyeee.bookmarkify.server.log.LogRetention
import java.time.LocalDateTime

@Service
class AdminActionLogServiceImpl :
    IAdminActionLogService, ServiceImpl<AdminActionLogMapper, AdminActionLogEntity>() {

    // ServiceImpl 自带的 log 是 MyBatis 的 Log 接口、没有占位符重载，同其它几个 *LogServiceImpl
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun record(entity: AdminActionLogEntity) {
        runCatching { save(entity) }.onFailure {
            // INFRA：审计写不进去意味着这段时间的后台操作无从追溯，而这正是审计存在的理由
            logger.warn(LogMarkers.INFRA, "[AdminActionLog] 审计写入失败 action={}: {}", entity.action, it.message)
        }
    }

    override fun adminListAll(params: AdminActionLogSearchParams): IPage<AdminActionLogEntity> =
        baseMapper.selectPage(params.toPage(), params.toWrapper())

    override fun purgeExpired(): Int {
        val before = LocalDateTime.now().minusDays(LogRetention.ADMIN_ACTION_DAYS)
        return baseMapper.delete(
            KtQueryWrapper(AdminActionLogEntity::class.java).lt(AdminActionLogEntity::createTime, before)
        )
    }
}
