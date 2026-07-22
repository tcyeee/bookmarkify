package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.ScrapperCallLogSearchParams
import top.tcyeee.bookmarkify.entity.ScrapperCallLogVO
import top.tcyeee.bookmarkify.entity.entity.ScrapperCallLogEntity

/**
 * scrapper 调用日志 Service
 */
interface IScrapperCallLogService : IService<ScrapperCallLogEntity> {
    fun adminListAll(params: ScrapperCallLogSearchParams): IPage<ScrapperCallLogVO>
}
