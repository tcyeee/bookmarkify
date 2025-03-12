package top.tcyeee.bookmarkify.controller.auth

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.request.LoginByClientForm
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
    @PostMapping("/login")
    @Operation(summary = "通过客户端ID登陆 @author tcyeee")
    fun login(@RequestBody loginForm: @Valid LoginByClientForm): UserAuthEntityVo {
        return loginService.loginByClientInfo(loginForm)
    }
}