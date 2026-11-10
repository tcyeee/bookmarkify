package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.AdminActionLogSearchParams
import top.tcyeee.bookmarkify.entity.entity.AdminActionLogEntity

/**
 * 后台操作审计日志 Service。写入方只有一个：[top.tcyeee.bookmarkify.config.audit.AdminAuditAspect]。
 */
interface IAdminActionLogService : IService<AdminActionLogEntity> {

    /**
     * 落一条审计。**永不抛出** —— 审计失败不该把管理员的那次操作一起弄挂
     * （同 `IUserBehaviorLogService.record` / `JsonConfigAccessor` 的审计写入）。
     * 代价是故障窗口里这段审计会缺失，所以写失败要带 INFRA 标记告警。
     */
    fun record(entity: AdminActionLogEntity)

    fun adminListAll(params: AdminActionLogSearchParams): IPage<AdminActionLogEntity>

    /** 清理过期记录，见 [top.tcyeee.bookmarkify.server.log.LogRetention] */
    fun purgeExpired(): Int
}
