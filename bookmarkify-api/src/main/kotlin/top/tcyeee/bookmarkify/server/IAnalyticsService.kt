package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.dto.AnalyticsOverview

/**
 * 后台分析看板数据服务：读取自托管 GoatCounter 的真实访问统计。
 */
interface IAnalyticsService {
    /**
     * 聚合后台看板所需的全部统计数据。
     * @param days 流量趋势折线展示的天数（默认 30）
     */
    fun overview(days: Int): AnalyticsOverview
}
