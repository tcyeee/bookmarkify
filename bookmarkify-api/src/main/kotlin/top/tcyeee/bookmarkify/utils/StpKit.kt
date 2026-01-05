package top.tcyeee.bookmarkify.utils

import cn.dev33.satoken.stp.StpLogic


/**
 * StpLogic 门面类，管理项目中所有的 StpLogic 账号体系
 * @author tcyeee
 * @date 1/5/26 18:56
 */
object StpKit {
    val ADMIN: StpLogic = StpLogic("admin")
    val USER: StpLogic = StpLogic("user")
}