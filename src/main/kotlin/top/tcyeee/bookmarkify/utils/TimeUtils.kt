package top.tcyeee.bookmarkify.utils

import cn.hutool.core.date.LocalDateTimeUtil
import kotlinx.datetime.*
import java.time.temporal.ChronoUnit

/**
 * @author tcyeee
 * @date 4/6/25 13:48
 */
object TimeUtils {
    fun now(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    fun yesterday(): LocalDateTime = parse(LocalDateTimeUtil.offset(LocalDateTimeUtil.now(), -1, ChronoUnit.DAYS))

    fun parse(timestamp: Long, isSconds: Boolean): LocalDateTime {
        val instant: Instant =
            if (isSconds) Instant.fromEpochSeconds(timestamp)
            else Instant.fromEpochMilliseconds(timestamp)
        return instant.toLocalDateTime(TimeZone.currentSystemDefault())
    }

    private fun parse(javaLocalDateTime: java.time.LocalDateTime): LocalDateTime =
        javaLocalDateTime.atZone(java.time.ZoneId.systemDefault())
            .toInstant().toKotlinInstant()
            .toLocalDateTime(TimeZone.currentSystemDefault())
}