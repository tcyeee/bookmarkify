package top.tcyeee.bookmarkify.controller.auth

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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

    @SaIgnore
    @GetMapping("/track")
    @Operation(summary = "创建用户标记")
    fun track(request: HttpServletRequest, response: HttpServletResponse): UserSessionInfo =
        userService.track(request, response)
}