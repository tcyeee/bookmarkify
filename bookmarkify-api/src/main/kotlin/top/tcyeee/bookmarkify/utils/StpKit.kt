package top.tcyeee.bookmarkify.utils

import cn.dev33.satoken.stp.StpLogic
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.entity.entity.RoleEnum


/**
 * StpLogic 门面类，管理项目中所有的 StpLogic 账号体系
 * @author tcyeee
 * @date 1/5/26 18:56
 */
@Component
object StpKit {
    val ADMIN: StpLogic = StpLogic(RoleEnum.ADMIN.name)
    val USER: StpLogic = StpLogic(RoleEnum.USER.name)
}