package top.tcyeee.bookmarkify.controller.auth

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.response.UserAuthEntityVo
import top.tcyeee.bookmarkify.server.impl.UserLoginServiceImpl

/**
 * @author tcyeee
 * @date 3/10/25 10:13
 */
@RestController
@Tag(name = "用户相关")
@RequestMapping("/auth")
class LoginController(
    private var loginService: UserLoginServiceImpl,
) {

    @SaIgnore
    @GetMapping("/loginByDeviceId")
    @Operation(summary = "通过客户端ID登录")
    fun loginByDeviceId(@RequestParam deviceId: String): UserAuthEntityVo {
        return loginService.loginByDeviceId(deviceId)
    }
}