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
import top.tcyeee.bookmarkify.entity.dto.PageCount
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

    companion object {
        /** 命中 429 限流时的最大重试次数（含首次共 RATE_LIMIT_RETRIES 次尝试） */
        private const val RATE_LIMIT_RETRIES = 4
        /** 响应未给出等待时长时的兜底退避（GoatCounter 限流窗口约 1s） */
        private const val DEFAULT_RETRY_WAIT_MS = 300L
        /**
         * 相邻两次 GoatCounter 调用的最小间隔。GoatCounter 限流 4 req/s，overview() 会顺序打多个接口，
         * 主动按 300ms 间隔（≈3.3 req/s）发出，从结构上保证不触发限流；429 重试仅作兜底。
         */
        private const val MIN_CALL_INTERVAL_MS = 300L

        /** 设备 / 屏幕尺寸 id -> 中文名（GoatCounter sizes 接口 name 为空，仅给出 id） */
        private val SIZE_LABELS = mapOf(
            "phone" to "手机",
            "tablet" to "平板",
            "desktop" to "桌面",
            "desktophd" to "高清桌面",
            "unknown" to "未知",
        )

        /** 自定义事件 name -> 中文名，用于事件明细展示 */
        private val EVENT_LABELS = mapOf(
            "bookmark-add" to "添加书签",
            "bookmark-link" to "书签跳转",
            "login-email" to "邮箱登录",
            "login-phone" to "手机登录",
            "login-password" to "密码登录",
        )

        /** 国家代码 -> 中文名，未命中则回退 GoatCounter 给出的英文名 */
        private val COUNTRY_LABELS = mapOf(
            "CN" to "中国", "HK" to "中国香港", "TW" to "中国台湾", "MO" to "中国澳门",
            "SG" to "新加坡", "US" to "美国", "JP" to "日本", "KR" to "韩国",
            "GB" to "英国", "DE" to "德国", "FR" to "法国", "CA" to "加拿大",
            "AU" to "澳大利亚", "RU" to "俄罗斯", "IN" to "印度", "NL" to "荷兰",
            "MY" to "马来西亚", "TH" to "泰国", "VN" to "越南", "ID" to "印度尼西亚",
        )
    }

    /** 上一次发出 GoatCounter 请求的时间戳（毫秒），用于全局限速 */
    @Volatile
    private var lastCallAtMs = 0L
    private val throttleLock = Any()

    override fun overview(days: Int): AnalyticsOverview {
        val today = LocalDate.now()
        val fullStart = today.minusDays(365)
        val recentStart = today.minusDays(6) // 含今天共 7 天

        // GoatCounter 限流 4 req/s，且该看板并非核心功能，因此只保留「单次请求即可拿到」的数据：
        // - total 拉两次（全量 + 近 7 日）供卡片的累计 / 最近对比；
        // - 其余分布各打一个接口，地理分布只到国家级、不再逐国家下钻省份（原本最多额外 6 个请求）。
        val totalFull = fetchStats("total", fullStart, today)
        val totalRecent = fetchStats("total", recentStart, today)
        val toprefsFull = fetchStats("toprefs", fullStart, today)
        val browsersFull = fetchStats("browsers", fullStart, today)
        val systemsFull = fetchStats("systems", fullStart, today)
        val locationsFull = fetchStats("locations", fullStart, today)
        val sizesFull = fetchStats("sizes", fullStart, today)
        val hitsFull = fetchStats("hits", fullStart, today)

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
        )

        return AnalyticsOverview(
            cards = cards,
            trend = buildTrend(totalFull, days),
            monthly = buildMonthly(totalFull),
            referrers = namedCounts(toprefsFull, blankName = "直接访问", limit = 8),
            browsers = namedCounts(browsersFull, limit = 8),
            systems = namedCounts(systemsFull, limit = 6),
            locations = countryCounts(locationsFull, limit = 12),
            sizes = sizeCounts(sizesFull),
            topPages = topPages(hitsFull, limit = 10),
            events = events(hitsFull),
            hourly = buildHourly(totalFull),
        )
    }

    /**
     * 调用 GoatCounter `/api/v0/stats/{path}` 并返回 JSON 根节点。
     *
     * GoatCounter 限流 4 req/s。overview() 顺序打多个接口，先用 [throttle] 按 [MIN_CALL_INTERVAL_MS]
     * 间隔主动限速、从结构上避免触发限流；万一仍命中 429，响应是纯文本「try again in 262ms」（非 JSON），
     * 按其给出的等待时间退避重试作兜底，避免把纯文本当 JSON 解析失败。
     */
    private fun fetchStats(path: String, start: LocalDate, end: LocalDate): JsonNode {
        val url = "${goatCounterConfig.baseUrl.trimEnd('/')}/api/v0/stats/$path?start=$start&end=$end"

        repeat(RATE_LIMIT_RETRIES) { attempt ->
            throttle()
            val response = runCatching {
                HttpUtil.createGet(url)
                    .header("Authorization", "Bearer ${goatCounterConfig.token}")
                    .timeout(15000)
                    .execute()
            }.getOrElse {
                log.error("GoatCounter 请求失败 path=$path: ${it.message}")
                throw CommonException(ErrorType.E306, it.message ?: it.toString())
            }

            val body = response.body()
            if (response.status == 429 && attempt < RATE_LIMIT_RETRIES - 1) {
                val waitMs = parseRetryAfterMs(body) ?: DEFAULT_RETRY_WAIT_MS
                log.warn("GoatCounter 限流 path=$path，第 ${attempt + 1} 次重试，等待 ${waitMs}ms")
                Thread.sleep(waitMs)
                return@repeat
            }
            if (!response.isOk) {
                throw CommonException(ErrorType.E306, "GoatCounter 返回 ${response.status}: ${body.take(120)}")
            }
            return runCatching { objectMapper.readTree(body) }
                .getOrElse { throw CommonException(ErrorType.E306, "GoatCounter 响应解析失败") }
        }
        throw CommonException(ErrorType.E306, "GoatCounter 限流重试仍失败 path=$path")
    }

    /** 全局限速：保证相邻两次 GoatCounter 请求间隔不小于 [MIN_CALL_INTERVAL_MS]，将突发压到限流阈值以下 */
    private fun throttle() {
        synchronized(throttleLock) {
            val wait = MIN_CALL_INTERVAL_MS - (System.currentTimeMillis() - lastCallAtMs)
            if (wait > 0) Thread.sleep(wait)
            lastCallAtMs = System.currentTimeMillis()
        }
    }

    /** 从限流响应体「try again in 262.824076ms」中解析需等待的毫秒数（向上取整 + 缓冲） */
    private fun parseRetryAfterMs(body: String?): Long? {
        val ms = body?.let { Regex("""try again in ([\d.]+)ms""").find(it) }
            ?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return ms.toLong() + 50
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

    /**
     * 地理分布（国家级）：直接用 `stats/locations` 一个接口的结果，国家名做中文映射（[COUNTRY_LABELS]），
     * 未命中回退 GoatCounter 给出的英文名。按访问量降序取前 [limit] 条。
     * （原先逐国家下钻省份的实现最多额外打 6 个请求，是触发限流的主要来源，已移除。）
     */
    private fun countryCounts(countries: JsonNode, limit: Int): List<NamedCount> =
        countries.path("stats")
            .filter { it.path("count").asLong() > 0 }
            .map {
                val code = it.path("id").asText("")
                val name = COUNTRY_LABELS[code] ?: it.path("name").asText(code).ifBlank { "未知" }
                NamedCount(name = name, count = it.path("count").asLong())
            }
            .sortedByDescending { it.count }
            .take(limit)

    /** sizes 接口 name 为空，按 id 映射中文名（[SIZE_LABELS]），未命中保留原 id */
    private fun sizeCounts(node: JsonNode): List<NamedCount> =
        node.path("stats").map {
            val id = it.path("id").asText("")
            NamedCount(name = SIZE_LABELS[id] ?: id.ifBlank { "未知" }, count = it.path("count").asLong())
        }.filter { it.count > 0 }

    /** 从 hits 取非事件的页面，按访问量降序前 [limit] 条 */
    private fun topPages(hits: JsonNode, limit: Int): List<PageCount> =
        hits.path("hits")
            .filterNot { it.path("event").asBoolean(false) }
            .map {
                val path = it.path("path").asText("")
                PageCount(
                    path = path,
                    title = it.path("title").asText("").ifBlank { path },
                    count = it.path("count").asLong(),
                )
            }
            .sortedByDescending { it.count }
            .take(limit)

    /** 从 hits 取自定义事件（event=true），name 映射中文（[EVENT_LABELS]） */
    private fun events(hits: JsonNode): List<NamedCount> =
        hits.path("hits")
            .filter { it.path("event").asBoolean(false) }
            .map {
                val raw = it.path("path").asText("")
                NamedCount(name = EVENT_LABELS[raw] ?: raw, count = it.path("count").asLong())
            }
            .sortedByDescending { it.count }

    /** 将每日的 24 小时桶按小时位累加，得到 0..23 的全量活跃分布 */
    private fun buildHourly(total: JsonNode): List<Long> {
        val buckets = LongArray(24)
        total.path("stats").forEach { day ->
            day.path("hourly").forEachIndexed { hour, v ->
                if (hour < 24) buckets[hour] += v.asLong()
            }
        }
        return buckets.toList()
    }
}
