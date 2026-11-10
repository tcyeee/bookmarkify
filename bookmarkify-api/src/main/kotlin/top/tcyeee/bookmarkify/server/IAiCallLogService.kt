package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.AiCallLogSearchParams
import top.tcyeee.bookmarkify.entity.AiCallLogVO
import top.tcyeee.bookmarkify.entity.entity.AiCallLogEntity

/**
 * 第三方 AI 调用日志 Service
 */
interface IAiCallLogService : IService<AiCallLogEntity> {
    fun adminListAll(params: AiCallLogSearchParams): IPage<AiCallLogVO>

    /**
     * 两档清理：先抹掉过期的请求/响应**原文**，再删掉整行过期的记录。
     *
     * 分两档是因为这张表的两部分价值衰减速度完全不同 —— 原文只对"刚刚那条判定"有用，
     * 而用量/成功率曲线要看得久。判据见 [top.tcyeee.bookmarkify.server.log.LogRetention]。
     *
     * @return 受影响的行数（抹正文 + 删行）
     */
    fun purgeExpired(): Int
}
