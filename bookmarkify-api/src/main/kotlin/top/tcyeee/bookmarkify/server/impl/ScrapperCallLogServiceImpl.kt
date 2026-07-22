package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.ScrapperCallLogSearchParams
import top.tcyeee.bookmarkify.entity.ScrapperCallLogVO
import top.tcyeee.bookmarkify.entity.entity.ScrapperCallLogEntity
import top.tcyeee.bookmarkify.mapper.ScrapperCallLogMapper
import top.tcyeee.bookmarkify.server.IScrapperCallLogService

/**
 * scrapper 调用日志 Service 实现
 */
@Service
class ScrapperCallLogServiceImpl :
    IScrapperCallLogService, ServiceImpl<ScrapperCallLogMapper, ScrapperCallLogEntity>() {

    override fun adminListAll(params: ScrapperCallLogSearchParams): IPage<ScrapperCallLogVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { ScrapperCallLogVO(it) }
}
