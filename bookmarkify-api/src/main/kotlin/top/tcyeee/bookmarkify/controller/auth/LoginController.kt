package top.tcyeee.bookmarkify.controller.auth

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.config.throttle.Throttle
import top.tcyeee.bookmarkify.entity.AccountLoginParams
import top.tcyeee.bookmarkify.entity.EmailVerifyParams
import top.tcyeee.bookmarkify.entity.GithubLoginParams
import top.tcyeee.bookmarkify.entity.GoogleLoginParams
import top.tcyeee.bookmarkify.entity.SendEmailParams
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.server.impl.UserServiceImpl

/**
 * @author tcyeee
 * @date 3/10/25 10:13
 */
@RestController
@Tag(name = "用户相关")
@RequestMapping("/auth")
@Validated
class LoginController(private val userService: UserServiceImpl) {

    @Throttle(byIp = true)
    @SaIgnore
    @PostMapping("/login")
    @Operation(summary = "账号密码登录，账号和密码均为Base64编码，密码为MD5加密后的字符串")
    fun login(@RequestBody params: AccountLoginParams): UserSessionInfo = userService.loginByAccount(params)

    @Throttle
    @GetMapping("/logout")
    @Operation(summary = "退出登录")
    fun logout(response: HttpServletResponse) = userService.loginOut(response)

    @Throttle(5 * 1000, byIp = true)
    @SaIgnore
    @PostMapping("captcha/email")
    @Operation(summary = "发送邮箱验证码，返回本次发送的区分代码（2 位大写字母，用于前端展示）")
    fun sendEmail(@RequestBody params: SendEmailParams): ResultWrapper = ResultWrapper.ok(userService.sendEmail(params.email))

    @Throttle(byIp = true)
    @SaIgnore
    @PostMapping("captcha/verifyEmail")
    @Operation(summary = "校验邮箱验证码并登录（邮箱不存在则注册）")
    fun verifyEmail(@RequestBody params: EmailVerifyParams): UserSessionInfo = userService.verifyEmail(params)

    @Throttle(byIp = true)
    @SaIgnore
    @PostMapping("/google")
    @Operation(summary = "校验 Google ID Token 并登录（Google 邮箱不存在则注册）")
    fun loginByGoogle(@RequestBody params: GoogleLoginParams): UserSessionInfo = userService.loginByGoogle(params)

    @Throttle(byIp = true)
    @SaIgnore
    @PostMapping("/github")
    @Operation(summary = "用 GitHub 授权码登录（GitHub 账号不存在则注册）")
    fun loginByGithub(@RequestBody params: GithubLoginParams): UserSessionInfo = userService.loginByGithub(params)
}
