package top.tcyeee.bookmarkify.utils

import cn.dev33.satoken.session.SaSession
import cn.dev33.satoken.stp.StpUtil
import cn.hutool.core.date.LocalDateTimeUtil
import cn.hutool.json.JSONUtil
import top.tcyeee.bookmarkify.entity.dto.UserInfo
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
    fun user(): UserInfo = user(StpUtil.getSession())
    private fun user(session: SaSession): UserInfo {
        val json: String = session.get("user").toString()
        return UserInfo(json)
    }

    fun getUidByToken(token: String): String? {
        val loginId = StpUtil.getLoginIdByToken(token) ?: return null
        val session = StpUtil.getSessionByLoginId(loginId) ?: return null
        return user(session).uid
    }


    /* 工具类方法 */
    fun yesterday(): LocalDateTime = LocalDateTimeUtil.offset(LocalDateTime.now(), -1, ChronoUnit.DAYS)
}