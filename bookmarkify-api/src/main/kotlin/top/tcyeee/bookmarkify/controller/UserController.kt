package top.tcyeee.bookmarkify.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.entity.UserDelParams
import top.tcyeee.bookmarkify.entity.UserInfoShow
import top.tcyeee.bookmarkify.entity.UserInfoUptateParams
import top.tcyeee.bookmarkify.server.ISmsService
import top.tcyeee.bookmarkify.server.IUserService
import top.tcyeee.bookmarkify.utils.BaseUtils
import top.tcyeee.bookmarkify.config.cache.RedisType
import top.tcyeee.bookmarkify.entity.CaptchaSmsParams
import top.tcyeee.bookmarkify.entity.SmsVerifyParams
import top.tcyeee.bookmarkify.utils.RedisUtils
import java.util.*

/**
 * @author tcyeee
 * @date 3/12/25 16:47
 */
@RestController
@Tag(name = "用户相关")
@RequestMapping("/user")
class UserController(
    private val userService: IUserService,
    private val smsService: ISmsService,
) {

    @GetMapping("info")
    @Operation(summary = "获取用户信息")
    fun info(): UserInfoShow = userService.me(BaseUtils.uid())

    @PostMapping("updateInfo")
    @Operation(summary = "修改用户信息")
    fun updateUsername(@RequestBody params: UserInfoUptateParams) = userService.updateInfo(params)

    @PostMapping("changePhone")
    @Operation(summary = "修改手机号-发送短信")
    fun changePhone(params: String) = userService.changePhone(params)

    @PostMapping("checkPhone")
    @Operation(summary = "修改手机号-确定短信")
    fun checkPhone(params: Int) = userService.checkPhone(params)

    @PostMapping("changeMail")
    @Operation(summary = "修改手机号-发送邮箱")
    fun changeMail(params: String) = userService.changeMail(params)

    @PostMapping("del")
    @Operation(summary = "账户注销")
    fun del(@RequestBody params: UserDelParams): Boolean = userService.del(params)

    @PostMapping("uploadAvatar")
    @Operation(summary = "上传头像", parameters = [Parameter(name = "file", description = "头像图片文件")])
    fun uploadAvatar(@RequestParam("file") file: MultipartFile): ResultWrapper {
        val fileUrl = userService.updateAvatar(file, BaseUtils.uid())
        return ResultWrapper.ok(fileUrl)
    }

    @GetMapping("captcha/image")
    @Operation(summary = "获取人机验证图案，返回base64")
    fun captchaImage(): ResultWrapper = userService.captchaImage(BaseUtils.uid())

    @PostMapping("captcha/sms")
    @Operation(summary = "校验人机验证码后发送短信验证码")
    fun sendSms(@RequestBody params: CaptchaSmsParams): Boolean = userService.sendSms(BaseUtils.uid(), params)

    @PostMapping("captcha/verify")
    @Operation(summary = "校验短信验证码并绑定手机号")
    fun verifySms(@RequestBody params: SmsVerifyParams): Boolean  = userService.verifySms(BaseUtils.uid(), params)
}