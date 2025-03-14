package top.tcyeee.bookmarkify.utils

import cn.dev33.satoken.stp.StpUtil
import cn.hutool.core.date.LocalDateTimeUtil
import top.tcyeee.bookmarkify.entity.dto.UserInfo
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * @author tcyeee
 * @date 3/10/24 23:27
 */
object BaseUtils {
    fun currentUid(): String = user().uid
    fun user(): UserInfo = StpUtil.getSession().get("user") as UserInfo
    fun yesterday(): LocalDateTime = LocalDateTimeUtil.offset(LocalDateTime.now(), -1, ChronoUnit.DAYS)
}