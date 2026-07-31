package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.AiCallLogSearchParams
import top.tcyeee.bookmarkify.entity.AiCallLogVO
import top.tcyeee.bookmarkify.entity.entity.AiCallLogEntity
import top.tcyeee.bookmarkify.mapper.AiCallLogMapper
import top.tcyeee.bookmarkify.server.IAiCallLogService

/**
 * 第三方 AI 调用日志 Service 实现
 */
@Service
class AiCallLogServiceImpl :
    IAiCallLogService, ServiceImpl<AiCallLogMapper, AiCallLogEntity>() {

    override fun adminListAll(params: AiCallLogSearchParams): IPage<AiCallLogVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { AiCallLogVO(it) }
}
