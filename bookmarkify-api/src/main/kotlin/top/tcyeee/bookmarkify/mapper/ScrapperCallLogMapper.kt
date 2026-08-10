package top.tcyeee.bookmarkify.mapper

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.annotations.Select
import org.springframework.context.annotation.Description
import top.tcyeee.bookmarkify.entity.dto.ScrapperErrorCodeRow
import top.tcyeee.bookmarkify.entity.dto.ScrapperFailedHostRow
import top.tcyeee.bookmarkify.entity.entity.ScrapperCallLogEntity
import java.time.LocalDateTime

@Mapper
interface ScrapperCallLogMapper : BaseMapper<ScrapperCallLogEntity> {

    // ⚠️ 下面两条语句的 SELECT 列顺序必须与 ScrapperFailedHostRow / ScrapperErrorCodeRow 的
    // 构造参数顺序**逐个对齐**：MyBatis 按位置映射构造参数，错位时类型兼容就会静默填错列
    @Select(
        """
            SELECT a.url_host           AS urlHost,
                   a.total_calls        AS totalCalls,
                   a.failed_calls       AS failedCalls,
                   a.failed_urls        AS failedUrls,
                   a.failed_duration_ms AS failedDurationMs,
                   a.total_duration_ms  AS totalDurationMs,
                   a.last_failed_at     AS lastFailedAt,
                   a.last_success_at    AS lastSuccessAt,
                   f.url                AS lastFailedUrl,
                   f.error_msg          AS lastErrorMsg,
                   f.error_code         AS lastErrorCode,
                   f.target_status      AS lastTargetStatus,
                   f.layer_used         AS lastLayerUsed
            FROM (SELECT url_host,
                         count(*)                                                 AS total_calls,
                         count(*) FILTER (WHERE NOT success)                      AS failed_calls,
                         count(DISTINCT url) FILTER (WHERE NOT success)           AS failed_urls,
                         -- ::bigint 不能省：PostgreSQL 的 sum(bigint) 返回 numeric，映射到
                         -- Kotlin 是 BigDecimal，往 Long 字段上灌会直接抛类型转换异常
                         coalesce(sum(duration_ms) FILTER (WHERE NOT success), 0)::bigint AS failed_duration_ms,
                         coalesce(sum(duration_ms), 0)::bigint                            AS total_duration_ms,
                         max(create_time) FILTER (WHERE NOT success)              AS last_failed_at,
                         max(create_time) FILTER (WHERE success)                  AS last_success_at
                  FROM scrapper_call_log
                  WHERE create_time >= #{from}
                  GROUP BY url_host
                  HAVING count(*) FILTER (WHERE NOT success) >= #{minFailures}) a
                     LEFT JOIN LATERAL (SELECT url, error_msg, error_code, target_status, layer_used
                                        FROM scrapper_call_log l
                                        WHERE l.url_host = a.url_host
                                          AND NOT l.success
                                          AND l.create_time >= #{from}
                                        ORDER BY l.create_time DESC
                                        LIMIT 1) f ON true
            ORDER BY (CASE #{sortField}
                          WHEN 'failedCalls' THEN a.failed_calls::double precision
                          WHEN 'failRate' THEN a.failed_calls::double precision / a.total_calls
                          ELSE a.failed_duration_ms::double precision
                      END) DESC, a.url_host
            LIMIT #{limit}
            """
    )
    @Description(
        """
        按域名聚合时间窗内的抓取失败画像，供后台「失败站点排行」使用。

        # 为什么排序默认按累计失败耗时

        只按失败次数排，会把「失败 20 次、每次 200ms」的站点排在「失败 7 次、每次 28 秒」前面，
        而真正该处理的是后者。生产上 console.cloud.tencent.com 正是这个形态：7 次调用全失败，
        每次平均 28.6 秒，全烧在一个必然打不开的登录墙 SPA 上。

        # 排序为什么写成 CASE 而不是拼字符串

        `${'$'}{}` 拼接排序字段是 SQL 注入面。这里三个可选口径的量纲不同（次数是整数、
        失败率是小数），统一 cast 成 double precision 后用 CASE 选择，既保住参数化绑定，
        又不必为每种排序各写一条语句。认不出的取值落到 ELSE，等同默认排序。

        # last_success_at 为什么必须一起查

        「这个域名是不是彻底抓不动」不能只看失败率：根路径 200、内容页 412 是很常见的形态
        （B 站就是），此时失败率很高但站点本身完全正常，据此按域名屏蔽会误伤。窗口内有没有
        成功过是区分这两种情况最直接的一个数。

        LATERAL 子查询取的是最近一次失败的现场（哪个地址、什么码、停在哪一层）——聚合数字
        说明规模，这一行说明长什么样，缺了它每次都得跳去调用日志页再翻一遍。

        @param from 时间窗下界（含）
        @param minFailures 失败次数门槛，低于它的域名不进榜，避免一次性抖动刷屏
        @param sortField failedDurationMs(默认) / failedCalls / failRate
        """
    )
    fun failedHostRanking(
        @Param("from") from: LocalDateTime,
        @Param("minFailures") minFailures: Int,
        @Param("sortField") sortField: String,
        @Param("limit") limit: Int,
    ): List<ScrapperFailedHostRow>

    @Select(
        """
            <script>
            SELECT url_host                        AS urlHost,
                   coalesce(error_code, 'UNKNOWN') AS errorCode,
                   count(*)                        AS "count"
            FROM scrapper_call_log
            WHERE NOT success
              AND create_time >= #{from}
              AND url_host IN
              <foreach item="host" collection="hosts" open="(" separator="," close=")">#{host}</foreach>
            GROUP BY url_host, coalesce(error_code, 'UNKNOWN')
            ORDER BY url_host, count(*) DESC
            </script>
            """
    )
    @Description(
        """
        排行页上这些域名各自的错误码分布。

        单独查一次而不是塞进聚合语句：失败率相同的两个域名，处置可能完全相反 ——
        403/406/412 是反爬，该去写站点 API 适配器把失败变成成功；连不上/DNS 失败是站点真的
        没了，交给巡检判失联即可；而 SCRAPPER_UNREACHABLE / HEADLESS_UNAVAILABLE 压根不是
        站点的问题，按站点归因就是错的。分布是做出这个区分唯一的依据，不是锦上添花。

        调用方必须保证 hosts 非空 —— 空集合会让 foreach 展开成 `IN ()`，那是语法错误。
        """
    )
    fun errorCodeBreakdown(
        @Param("from") from: LocalDateTime,
        @Param("hosts") hosts: Collection<String>,
    ): List<ScrapperErrorCodeRow>
}
