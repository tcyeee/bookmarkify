package top.tcyeee.bookmarkify.entity.dto

/**
 * 后台分析看板聚合数据，全部来源于自托管 GoatCounter（bookmarkify 站点）。
 */
data class AnalyticsOverview(
    /** 顶部四张统计卡片：访问量 / 事件数 / 来源数 / 浏览器数 */
    val cards: List<StatCard>,
    /** 流量趋势：按天的访问量与 7 日移动平均 */
    val trend: TrendSeries,
    /** 月访问量：近 12 个月的访问量 */
    val monthly: MonthlySeries,
    /** 访问来源（引荐域名） */
    val referrers: List<NamedCount>,
    /** 浏览器分布 */
    val browsers: List<NamedCount>,
    /** 操作系统 / 系统分布 */
    val systems: List<NamedCount>,
)

/** 统计卡片：[total] 为统计区间累计值，[recent] 为近 7 日值 */
data class StatCard(
    val key: String,
    val title: String,
    val total: Long,
    val recent: Long,
)

/** 按天的趋势序列，[dates] 与 [pageviews]/[trend] 等长 */
data class TrendSeries(
    val dates: List<String>,
    val pageviews: List<Long>,
    /** 7 日移动平均，用于平滑趋势线 */
    val trend: List<Long>,
)

/** 按月聚合的访问量，[months] 形如 "2026-06" */
data class MonthlySeries(
    val months: List<String>,
    val pageviews: List<Long>,
)

/** 通用「名称 - 计数」条目，用于饼图 / 雷达图 */
data class NamedCount(
    val name: String,
    val count: Long,
)
