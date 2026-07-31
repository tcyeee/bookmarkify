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
}
