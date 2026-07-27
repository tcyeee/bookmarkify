package top.tcyeee.bookmarkify.controller.user

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.entity.ChangePasswordParams
import top.tcyeee.bookmarkify.entity.GithubLoginParams
import top.tcyeee.bookmarkify.entity.GoogleLoginParams
import top.tcyeee.bookmarkify.entity.UserDelParams
import top.tcyeee.bookmarkify.entity.UserInfoShow
import top.tcyeee.bookmarkify.entity.UserInfoUpdateParams
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.BaseUtils

/**
 * @author tcyeee
 * @date 3/12/25 16:47
 */
@RestController
@Tag(name = "用户相关")
@RequestMapping("/user")
class UserController(private val userService: IUserService) {

    @GetMapping("info")
    @Operation(summary = "获取用户信息")
    fun info(): UserInfoShow = userService.me(BaseUtils.uid())

    @GetMapping("avatar-url")
    @Operation(summary = "获取当前用户头像签名 URL（1 小时有效，按需调用，请勿持久化）")
    fun avatarUrl(): ResultWrapper {
        // 显式包 ResultWrapper：GlobalExceptionHandler.beforeBodyWrite 对裸 String 返回值不做包装，
        // 直接透传会破坏前端统一的 Result<T>{code,msg,data,ok} 解析(同 LoginController.sendEmail 的处理方式)。
        val url = userService.avatarSignedUrl(BaseUtils.uid()) ?: return ResultWrapper.ok()
        return ResultWrapper.ok(url)
    }

    @PostMapping("updateInfo")
    @Operation(summary = "修改用户信息")
    fun updateUsername(@RequestBody params: UserInfoUpdateParams) = userService.updateInfo(params)

    // 旧的手机号绑定/改绑接口直接改库且无验证，已随手机号功能一并下线。
    // 邮箱验证/绑定统一走 /auth/captcha/verifyEmail。

    @PostMapping("changePassword")
    @Operation(summary = "修改密码，旧密码和新密码均为Base64编码的MD5字符串")
    fun changePassword(@RequestBody params: ChangePasswordParams): Boolean =
        userService.changePassword(BaseUtils.uid(), params)

    @PostMapping("google/bind")
    @Operation(summary = "关联 Google 账号到当前账户")
    fun bindGoogle(@RequestBody params: GoogleLoginParams): UserInfoShow =
        userService.bindGoogle(BaseUtils.uid(), params)

    @PostMapping("google/unbind")
    @Operation(summary = "解绑当前账户的 Google 关联")
    fun unbindGoogle(): UserInfoShow = userService.unbindGoogle(BaseUtils.uid())

    @PostMapping("github/bind")
    @Operation(summary = "关联 GitHub 账号到当前账户")
    fun bindGithub(@RequestBody params: GithubLoginParams): UserInfoShow =
        userService.bindGithub(BaseUtils.uid(), params)

    @PostMapping("github/unbind")
    @Operation(summary = "解绑当前账户的 GitHub 关联")
    fun unbindGithub(): UserInfoShow = userService.unbindGithub(BaseUtils.uid())

    @PostMapping("del")
    @Operation(summary = "账户注销")
    fun del(@RequestBody params: UserDelParams): Boolean = userService.del(params)

    @PostMapping("uploadAvatar")
    @Operation(summary = "上传头像", parameters = [Parameter(name = "file", description = "头像图片文件")])
    fun uploadAvatar(@RequestParam("file") file: MultipartFile): ResultWrapper {
        val fileUrl = userService.updateAvatar(file, BaseUtils.uid())
        return ResultWrapper.ok(fileUrl)
    }
}