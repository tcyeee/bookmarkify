package top.tcyeee.bookmarkify.utils

import cn.dev33.satoken.session.SaSession
import cn.dev33.satoken.stp.StpUtil
import cn.hutool.core.codec.Base64
import cn.hutool.core.date.LocalDateTimeUtil
import cn.hutool.crypto.SecureUtil
import cn.hutool.json.JSONUtil
import top.tcyeee.bookmarkify.entity.dto.UserInfo
import top.tcyeee.bookmarkify.entity.po.UserEntity
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
        return UserInfo(session)
    }

    fun getUidByToken(token: String): String? {
        val loginId = StpUtil.getLoginIdByToken(token) ?: return null
        val session = StpUtil.getSessionByLoginId(loginId) ?: return null
        return user(session).uid
    }

    /* 工具类方法 */
    fun yesterday(): LocalDateTime = LocalDateTimeUtil.offset(LocalDateTime.now(), -1, ChronoUnit.DAYS)

    fun userToJson(user: UserEntity): String {
        val res = JSONUtil.createObj()
        res["uid"] = user.uid
        res["nickName"] = user.nickName
        res["email"] = user.email
        res["phone"] = user.phone
        return JSONUtil.toJsonStr(res)
    }

    /* base64 to Md5 */
    fun pwd(password64: String): String = SecureUtil.md5(Base64.decodeStr(password64))
}