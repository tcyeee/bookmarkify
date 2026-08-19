package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaIgnore
import cn.dev33.satoken.stp.SaTokenInfo
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.config.throttle.Throttle
import top.tcyeee.bookmarkify.entity.AdminLoginParams
import top.tcyeee.bookmarkify.entity.UserInfoShow
import top.tcyeee.bookmarkify.entity.UserInfoUpdateParams
import top.tcyeee.bookmarkify.entity.entity.RoleEnum
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.StpKit

/**
 * @author tcyeee
 * @date 1/5/26 19:19
 */
@RestController
@RequestMapping("/admin")
class AdminController(private val userService: IUserService) {

    @Throttle(byIp = true)
    @SaIgnore
    @PostMapping("/login")
    fun login(@RequestBody params: AdminLoginParams): SaTokenInfo {
        val user = userService.findByNameAndPwd(params.account, params.password)
            ?: throw CommonException(ErrorType.E109)
        if (user.role != RoleEnum.ADMIN) throw CommonException(ErrorType.E109)

        StpKit.ADMIN.login(user.id)
        return StpKit.ADMIN.tokenInfo
    }

    @GetMapping("/info")
    fun info(): UserInfoShow = userService.me(StpKit.ADMIN.loginIdAsString)

    @PostMapping("/info")
    fun updateInfo(@RequestBody params: UserInfoUpdateParams): Boolean =
        userService.updateInfo(params, StpKit.ADMIN.loginIdAsString)

    @GetMapping("/avatar-url")
    fun avatarUrl(): ResultWrapper {
        // 显式包 ResultWrapper：同 UserController.avatarUrl 的处理方式
        val url = userService.avatarSignedUrl(StpKit.ADMIN.loginIdAsString) ?: return ResultWrapper.ok()
        return ResultWrapper.ok(url)
    }

    @PostMapping("/avatar")
    fun uploadAvatar(@RequestParam("file") file: MultipartFile): ResultWrapper {
        val fileUrl = userService.updateAvatar(file, StpKit.ADMIN.loginIdAsString)
        return ResultWrapper.ok(fileUrl)
    }

    @GetMapping("/codes")
    fun codes(): List<String> = listOf("AC_100100", "AC_100110", "AC_100120", "AC_100010")

    @RequestMapping("/logout")
    fun logout() = StpKit.ADMIN.logout()
}
