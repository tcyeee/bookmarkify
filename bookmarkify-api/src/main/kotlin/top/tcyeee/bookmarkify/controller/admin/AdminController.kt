package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaIgnore
import cn.dev33.satoken.stp.SaTokenInfo
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.AdminLoginParams
import top.tcyeee.bookmarkify.entity.UserInfoShow
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.StpKit

/**
 * @author tcyeee
 * @date 1/5/26 19:19
 */
@RestController
@RequestMapping("/admin")
class AdminController(
    private val userService: IUserService
) {

    @SaIgnore
    @PostMapping("/login")
    fun login(@RequestBody params: AdminLoginParams): SaTokenInfo {
        val user = userService.findByNameAndPwd(params.account, params.password)
        if (user != null) {
            StpKit.ADMIN.login(user.id)
            return StpKit.ADMIN.tokenInfo
        }
        throw CommonException(ErrorType.E109)
    }

    @GetMapping("/info")
    fun info(): UserInfoShow {
        StpKit.ADMIN.checkLogin()
        return UserInfoShow(
            uid = StpKit.ADMIN.loginIdAsString,
            nickName = "Administrator",
            avatarUrl = "https://unpkg.com/@vbenjs/static-source@0.1.7/source/avatar-v1.webp"
        )
    }

    @RequestMapping("/logout")
    fun logout() {
        StpKit.ADMIN.logout()
    }
}
