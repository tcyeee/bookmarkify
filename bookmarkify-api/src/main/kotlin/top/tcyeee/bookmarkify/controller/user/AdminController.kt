package top.tcyeee.bookmarkify.controller.user

import cn.dev33.satoken.annotation.SaIgnore
import cn.dev33.satoken.stp.SaTokenInfo
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.entity.AdminLoginConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.AdminLoginParams
import top.tcyeee.bookmarkify.utils.StpKit

/**
 * @author tcyeee
 * @date 1/5/26 19:19
 */
@RestController
@RequestMapping("/admin")
class AdminController(private val adminLoginConfig: AdminLoginConfig) {

    @SaIgnore
    @PostMapping("/login")
    fun login(@RequestBody params: AdminLoginParams): SaTokenInfo {
        if (params.account == adminLoginConfig.account &&
                        params.password == adminLoginConfig.password
        ) {
            StpKit.ADMIN.login(params.account)
            return StpKit.ADMIN.tokenInfo
        }
        throw CommonException(ErrorType.E109)
    }
}
