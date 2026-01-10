package top.tcyeee.bookmarkify.utils

import cn.dev33.satoken.session.SaSession
import cn.hutool.core.date.LocalDateTimeUtil
import cn.hutool.core.util.IdUtil
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.utils.BaseUtils.user
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 系统基础方法
 *
 * @author tcyeee
 * @date 3/10/24 23:27
 */
object BaseUtils {
    /* 用户相关方法 */
    fun uid(): String = user()?.uid ?: throw NullPointerException()
    fun user(): UserSessionInfo? = StpKit.USER.session.get(SaSession.USER)?.let { UserSessionInfo(it) }

    fun sessionRegisterDeviceId(
        request: HttpServletRequest, response: HttpServletResponse, config: ProjectConfig
    ): String {
        // 在session中获取&生成设备ID
        val deviceId = request.cookies?.find { it.name == config.uidCookieName }?.value ?: IdUtil.fastUUID()
        // 在session中存储设备ID
        Cookie(config.uidCookieName, deviceId).apply { maxAge = config.uidCookieMaxAge; path = config.uidCookiePath }
            .also { cookie -> response.addCookie(cookie) }
        return deviceId
    }
}

val APP_UID: String = user()?.uid ?: throw NullPointerException()

/* 工具类方法 */
fun yesterday(): LocalDateTime = LocalDateTimeUtil.offset(LocalDateTime.now(), -1, ChronoUnit.DAYS)

// 获取当前的系统环境
fun currentEnvironment(): CurrentEnvironment {
    return when (System.getenv("ENV")) {
        "local" -> CurrentEnvironment.LOCAL
        "prod" -> CurrentEnvironment.PROD
        else -> CurrentEnvironment.LOCAL
    }
}

enum class CurrentEnvironment {
    /* 本地测试环境 */
    LOCAL,

    /* 线上发布环境 */
    PROD,
}