package top.tcyeee.bookmarkify.utils

import cn.dev33.satoken.session.SaSession
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo

/**
 * 系统基础方法
 *
 * @author tcyeee
 * @date 3/10/24 23:27
 */
object BaseUtils {
    /* 用户相关方法 */
    fun uid(): String = user()?.uid ?: throw CommonException(ErrorType.E101)
    fun user(): UserSessionInfo? = StpKit.USER.session.get(SaSession.USER)?.let { UserSessionInfo(it) }
}

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