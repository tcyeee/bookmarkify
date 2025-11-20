package top.tcyeee.bookmarkify.controller.auth

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.entity.request.UserDelParams
import top.tcyeee.bookmarkify.entity.request.UserInfoUptateParams
import top.tcyeee.bookmarkify.entity.response.UserInfoShow
import top.tcyeee.bookmarkify.server.IUserService

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
    fun info(): UserInfoShow = userService.userInfo()

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
}
