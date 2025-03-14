package top.tcyeee.bookmarkify.utils

import cn.dev33.satoken.stp.StpUtil
import top.tcyeee.bookmarkify.entity.dto.UserInfo

/**
 * @author tcyeee
 * @date 3/10/24 23:27
 */
object BaseUtils {
    fun currentUid(): String = user().uid
    fun user(): UserInfo = StpUtil.getSession().get("user") as UserInfo
}