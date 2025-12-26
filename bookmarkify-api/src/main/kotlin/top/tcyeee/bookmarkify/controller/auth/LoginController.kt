package top.tcyeee.bookmarkify.controller.auth

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.entity.CaptchaSmsParams
import top.tcyeee.bookmarkify.entity.EmailVerifyParams
import top.tcyeee.bookmarkify.entity.SendEmailParams
import top.tcyeee.bookmarkify.entity.SmsVerifyParams
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.server.impl.UserServiceImpl
import top.tcyeee.bookmarkify.utils.BaseUtils

/**
 * @author tcyeee
 * @date 3/10/25 10:13
 */
@RestController
@Tag(name = "用户相关")
@RequestMapping("/auth")
@Validated
class LoginController(private val userService: UserServiceImpl) {

    @SaIgnore
    @GetMapping("/track")
    @Operation(summary = "创建用户标记")
    fun track(request: HttpServletRequest, response: HttpServletResponse): UserSessionInfo =
        userService.track(request, response)

    @GetMapping("/logout")
    @Operation(summary = "退出登录")
    fun logout() = userService.loginOut()

    @GetMapping("captcha/image")
    @Operation(summary = "获取人机验证图案，返回base64")
    fun captchaImage(): ResultWrapper = userService.captchaImage(BaseUtils.uid())

    @PostMapping("captcha/sms")
    @Operation(summary = "校验人机验证码后发送短信验证码")
    fun sendSms(@RequestBody params: CaptchaSmsParams): Boolean = userService.sendSms(BaseUtils.uid(), params)

    @PostMapping("captcha/verify")
    @Operation(summary = "校验短信验证码并绑定手机号")
    fun verifySms(
        request: HttpServletRequest, response: HttpServletResponse, @RequestBody params: SmsVerifyParams
    ): UserSessionInfo = userService.verifySms(request, response, BaseUtils.uid(), params)

    @PostMapping("captcha/email")
    @Operation(summary = "发送邮箱验证码")
    fun sendEmail(@RequestBody params: SendEmailParams): Boolean = userService.sendEmail(BaseUtils.uid(), params.email)

    @PostMapping("captcha/verifyEmail")
    @Operation(summary = "校验邮箱验证码并绑定邮箱")
    fun verifyEmail(
        request: HttpServletRequest, response: HttpServletResponse, @RequestBody params: EmailVerifyParams
    ): UserSessionInfo = userService.verifyEmail(request, response, BaseUtils.uid(), params)
}
