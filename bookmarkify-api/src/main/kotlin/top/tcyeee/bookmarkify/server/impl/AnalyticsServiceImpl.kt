package top.tcyeee.bookmarkify.server.impl

import cn.hutool.http.HttpUtil
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.GoatCounterConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.AnalyticsOverview
import top.tcyeee.bookmarkify.entity.dto.MonthlySeries
import top.tcyeee.bookmarkify.entity.dto.NamedCount
import top.tcyeee.bookmarkify.entity.dto.StatCard
import top.tcyeee.bookmarkify.entity.dto.TrendSeries
import top.tcyeee.bookmarkify.server.IAnalyticsService
import top.tcyeee.bookmarkify.config.log
import java.time.LocalDate

/**
 * 通过 GoatCounter REST API（stats/total、stats/toprefs 等）拉取真实访问数据并聚合为看板所需结构。
 * 统计区间取近一年（站点上线时间短，约等于全量），近 7 日作为卡片的「最近」值。
 */
@Service
class AnalyticsServiceImpl(
    private val goatCounterConfig: GoatCounterConfig,
    private val objectMapper: ObjectMapper,
) : IAnalyticsService {

    override fun overview(days: Int): AnalyticsOverview {
        val today = LocalDate.now()
        val fullStart = today.minusDays(365)
        val recentStart = today.minusDays(6) // 含今天共 7 天

        val totalFull = fetchStats("total", fullStart, today)
        val totalRecent = fetchStats("total", recentStart, today)
        val toprefsFull = fetchStats("toprefs", fullStart, today)
        val toprefsRecent = fetchStats("toprefs", recentStart, today)
        val browsersFull = fetchStats("browsers", fullStart, today)
        val browsersRecent = fetchStats("browsers", recentStart, today)
        val systemsFull = fetchStats("systems", fullStart, today)

        val cards = listOf(
            StatCard(
                key = "pageviews",
                title = "访问量",
                total = totalFull.path("total").asLong(),
                recent = totalRecent.path("total").asLong(),
            ),
            StatCard(
                key = "events",
                title = "事件数",
                total = totalFull.path("total_events").asLong(),
                recent = totalRecent.path("total_events").asLong(),
            ),
            StatCard(
                key = "referrers",
                title = "来源数",
                total = toprefsFull.path("stats").size().toLong(),
                recent = toprefsRecent.path("stats").size().toLong(),
            ),
            StatCard(
                key = "browsers",
                title = "浏览器数",
                total = browsersFull.path("stats").size().toLong(),
                recent = browsersRecent.path("stats").size().toLong(),
            ),
        )

        return AnalyticsOverview(
            cards = cards,
            trend = buildTrend(totalFull, days),
            monthly = buildMonthly(totalFull),
            referrers = namedCounts(toprefsFull, blankName = "直接访问", limit = 8),
            browsers = namedCounts(browsersFull, limit = 8),
            systems = namedCounts(systemsFull, limit = 6),
        )
    }

    /** 调用 GoatCounter `/api/v0/stats/{path}` 并返回 JSON 根节点 */
    private fun fetchStats(path: String, start: LocalDate, end: LocalDate): JsonNode {
        val url = "${goatCounterConfig.baseUrl.trimEnd('/')}/api/v0/stats/$path?start=$start&end=$end"
        val body = runCatching {
            HttpUtil.createGet(url)
                .header("Authorization", "Bearer ${goatCounterConfig.token}")
                .timeout(15000)
                .execute()
                .body()
        }.getOrElse {
            log.error("GoatCounter 请求失败 path=$path: ${it.message}")
            throw CommonException(ErrorType.E306, it.message ?: it.toString())
        }
        return runCatching { objectMapper.readTree(body) }
            .getOrElse { throw CommonException(ErrorType.E306, "GoatCounter 响应解析失败") }
    }

    /** 从 total 的每日序列取末尾 [days] 天，构造折线（访问量 + 7 日移动平均） */
    private fun buildTrend(total: JsonNode, days: Int): TrendSeries {
        val stats = total.path("stats").toList()
        val tail = stats.takeLast(days.coerceAtLeast(1))
        val dates = tail.map { it.path("day").asText() }
        val pageviews = tail.map { it.path("daily").asLong() }
        val trend = pageviews.indices.map { i ->
            val window = pageviews.subList(maxOf(0, i - 6), i + 1)
            window.sum() / window.size
        }
        return TrendSeries(dates = dates, pageviews = pageviews, trend = trend)
    }

    /** 将每日访问量按 YYYY-MM 聚合，取近 12 个月 */
    private fun buildMonthly(total: JsonNode): MonthlySeries {
        val byMonth = LinkedHashMap<String, Long>()
        total.path("stats").forEach { day ->
            val month = day.path("day").asText().take(7) // "2026-06-21" -> "2026-06"
            if (month.length == 7) {
                byMonth[month] = (byMonth[month] ?: 0) + day.path("daily").asLong()
            }
        }
        val months = byMonth.keys.toList().takeLast(12)
        return MonthlySeries(months = months, pageviews = months.map { byMonth[it] ?: 0 })
    }

    /** 把 stats 列表转为 NamedCount，空名称替换为 [blankName]，取前 [limit] 条 */
    private fun namedCounts(node: JsonNode, blankName: String? = null, limit: Int): List<NamedCount> =
        node.path("stats").take(limit).map {
            val raw = it.path("name").asText("")
            val name = raw.ifBlank { blankName ?: "未知" }
            NamedCount(name = name, count = it.path("count").asLong())
        }
}
