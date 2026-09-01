package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.ScrapperCallLogSearchParams
import top.tcyeee.bookmarkify.entity.ScrapperCallLogStatsVO
import top.tcyeee.bookmarkify.entity.ScrapperCallLogVO
import top.tcyeee.bookmarkify.entity.ScrapperFailedHostParams
import top.tcyeee.bookmarkify.entity.ScrapperFailedHostVO
import top.tcyeee.bookmarkify.entity.entity.ScrapperCallLogEntity

/**
 * scrapper 调用日志 Service
 */
interface IScrapperCallLogService : IService<ScrapperCallLogEntity> {
    fun adminListAll(params: ScrapperCallLogSearchParams): IPage<ScrapperCallLogVO>

    /**
     * 当前筛选范围下的汇总（总数 / 成功 / 失败 / 缓存命中）。
     *
     * 单独一个接口而不是塞进列表响应：`IPage` 的形状是固定的，而这个数应当独立于翻页存在
     * —— 翻到第 3 页时看到的仍是整个范围的成功率。见 [ScrapperCallLogStatsVO]。
     */
    fun adminStats(params: ScrapperCallLogSearchParams): ScrapperCallLogStatsVO

    /**
     * 失败站点排行：按域名聚合窗口内的抓取失败画像。
     *
     * 调用日志是一次调用一行，翻它只看得到个例，回答不了「哪些站点在反复失败、每次烧掉多少秒」。
     * 而这两个数才是决定要不要为某类站点做特殊处理（写站点 API 适配器、延长熔断、乃至不再重试）
     * 的依据。见 [ScrapperFailedHostVO]。
     */
    fun failedHostRanking(params: ScrapperFailedHostParams): List<ScrapperFailedHostVO>
}
