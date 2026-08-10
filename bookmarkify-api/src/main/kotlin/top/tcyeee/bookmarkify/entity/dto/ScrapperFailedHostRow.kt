package top.tcyeee.bookmarkify.entity.dto

import java.time.LocalDateTime

/**
 * 失败站点排行聚合查询的一行原始结果。
 *
 * 与 `ScrapperFailedHostVO` 分开而不是直接查成 VO：VO 上还有两项要在服务层补
 * （站点图标、错误码分布），那意味着它们得是带默认值的 `var`——而一旦 data class 的
 * **全部**参数都有默认值，Kotlin 会额外生成一个无参构造，MyBatis 会优先挑中它，
 * 然后走 setter 回填，聚合出来的数字就会静默地全变成 0。同样的坑在 [StuckLoadingStats]
 * 上已经踩过一次，那里的对策是"一个默认值都不给"，这里则是把两种角色拆成两个类。
 *
 * 所以本类刻意**不给任何默认值**，可空的字段用可空类型表达。
 *
 * ⚠️ **改字段顺序就要同步改 SQL 的 SELECT 列顺序。** MyBatis 给构造函数自动映射时按**位置**
 * 取列，别名只是让人读得懂（PostgreSQL 还会把不加引号的别名全部小写化，指望它按名字对上并不
 * 稳妥）。顺序错位不会报错——类型恰好兼容时它会安静地把 failedCalls 填进 failedUrls。
 */
data class ScrapperFailedHostRow(
    val urlHost: String,
    val totalCalls: Long,
    val failedCalls: Long,
    val failedUrls: Long,
    val failedDurationMs: Long,
    val totalDurationMs: Long,
    val lastFailedAt: LocalDateTime?,
    /** 窗口内最近一次成功；为空表示这个窗口里一次都没成功过 */
    val lastSuccessAt: LocalDateTime?,
    val lastFailedUrl: String?,
    val lastErrorMsg: String?,
    val lastErrorCode: String?,
    val lastTargetStatus: Int?,
    val lastLayerUsed: String?,
)

/** 某域名在窗口内某个错误码出现了多少次 */
data class ScrapperErrorCodeRow(
    val urlHost: String,
    /** 迁移 `2026-08-10_scrapper_call_log_error_code.sql` 之前的历史行没有这个值，SQL 里统一归为 `UNKNOWN` */
    val errorCode: String,
    val count: Long,
)
