package top.tcyeee.bookmarkify.utils

import cn.dev33.satoken.session.SaSession
import cn.dev33.satoken.stp.StpUtil
import cn.hutool.core.codec.Base64
import cn.hutool.core.date.LocalDateTimeUtil
import cn.hutool.core.util.IdUtil
import cn.hutool.crypto.SecureUtil
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
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
    fun uid(): String = user().uid
    fun user(): UserSessionInfo = user(StpUtil.getSession())
    private fun user(session: SaSession): UserSessionInfo = UserSessionInfo(session)

    fun getUidByToken(token: String): String? = StpUtil.getLoginIdByToken(token)
        ?.let { StpUtil.getSessionByLoginId(it) }
        ?.let { user(it).uid }

    /**
     * Registers and returns the deviceId (anonymous user ID).
     *
     * Logic:
     * 1. Try to read the deviceId from cookies.
     * 2. If not found, generate a new ID, create a cookie, and attach it to the response.
     * 3. Always return the final deviceId.
     */
    fun registerDeviceId(request: HttpServletRequest, response: HttpServletResponse, config: ProjectConfig): String =
        request.cookies
            ?.find { it.name == config.uidCookieName }?.value
            ?: "SERVER-${IdUtil.fastUUID()}".also {
                Cookie(config.uidCookieName, it).apply {
                    maxAge = config.uidCookieMaxAge
                    path = config.uidCookiePath
                }.also { cookie -> response.addCookie(cookie) }
            }
}

/* base64 to Md5 */
fun pwd(password64: String): String = SecureUtil.md5(Base64.decodeStr(password64))

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