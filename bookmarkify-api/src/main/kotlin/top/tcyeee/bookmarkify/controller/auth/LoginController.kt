package top.tcyeee.bookmarkify.controller.auth

import cn.dev33.satoken.annotation.SaIgnore
import cn.hutool.core.util.IdUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.entity.ProjectConfig
import top.tcyeee.bookmarkify.entity.UserAuthEntityVo
import top.tcyeee.bookmarkify.server.impl.UserLoginServiceImpl

/**
 * @author tcyeee
 * @date 3/10/25 10:13
 */
@RestController
@Tag(name = "用户相关")
@RequestMapping("/auth")
@Validated
class LoginController(
    private val loginService: UserLoginServiceImpl,
    private val projectConfig: ProjectConfig,
) {

    @SaIgnore
    @GetMapping("/loginByDeviceId")
    @Operation(summary = "通过客户端ID登录")
    fun loginByDeviceId(
        @RequestParam
        @NotBlank
        @Size(min = 5, message = "deviceId length must be at least 5 characters")
        deviceId: String
    ): UserAuthEntityVo {
        return loginService.loginByDeviceId(deviceId)
    }

    @GetMapping("/track")
    fun track(request: HttpServletRequest, response: HttpServletResponse): String {
        val uidCookie = request.cookies?.find { it.name == projectConfig.uidCookieName }
        if (uidCookie != null) return uidCookie.value

        val newUid = "SERVER-" + IdUtil.fastUUID()
        val cookie = Cookie(projectConfig.uidCookieName, newUid)
        cookie.maxAge = projectConfig.uidCookieMaxAge
        cookie.path = projectConfig.uidCookiePath
        response.addCookie(cookie)

        return newUid
    }

}