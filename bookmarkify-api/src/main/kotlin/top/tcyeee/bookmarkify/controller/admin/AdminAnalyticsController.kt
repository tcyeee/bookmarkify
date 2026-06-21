package top.tcyeee.bookmarkify.controller.admin

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.dto.AnalyticsOverview
import top.tcyeee.bookmarkify.server.IAnalyticsService

/**
 * 后台分析看板：返回 GoatCounter 的真实访问统计。
 *
 * @author tcyeee
 */
@RestController
@RequestMapping("/admin/analytics")
class AdminAnalyticsController(private val analyticsService: IAnalyticsService) {

    /** 看板聚合数据，[days] 控制流量趋势折线的天数 */
    @GetMapping("/overview")
    fun overview(@RequestParam(defaultValue = "30") days: Int): AnalyticsOverview =
        analyticsService.overview(days)
}
