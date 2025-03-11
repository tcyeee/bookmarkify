package top.tcyeee.bookmarkify.controller.auth

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.entity.po.UserEntity
import top.tcyeee.bookmarkify.entity.request.LoginByClientForm
import top.tcyeee.bookmarkify.entity.response.UserEntityVo
import top.tcyeee.bookmarkify.server.impl.UserLoginServiceImpl

/**
 * @author tcyeee
 * @date 3/10/25 10:13
 */
@RestController
@Tag(name = "用户相关")
@RequestMapping("/auth")
class LoginController(
    private var loginService: UserLoginServiceImpl

//    private var authenticationService: AuthenticationService,
//    private var bookmarkUserLinkService: IBookmarkUserLinkService,
//    private var homeItemService: IHomeItemService,
//    private var userService: IUserService
) {


    @SaIgnore
    @PostMapping("/login")
    @Operation(summary = "通过客户端ID登陆 @author tcyeee")
    fun login(@RequestBody loginForm: @Valid LoginByClientForm): UserEntityVo {
        return loginService.loginByClientInfo(loginForm)
    }

//    @PostMapping("/register")
//    @Operation(summary = "注册")
//    fun signup(@RequestBody request: SignUpRequest): ResponseEntity<UserStore> =
//        ResponseEntity.ok(authenticationService.register(request))
//
//    @Transactional
//    @PostMapping("/login")
//    @Operation(summary = "登录")
//    fun signin(@RequestBody request: SignInRequest): UserStore {
//        var userStore = authenticationService.login(request)
//        var currentUid = SysUtils.currentUid()
//        bookmarkUserLinkService.copy(currentUid, userStore.uid)
//        homeItemService.copy(currentUid, userStore.uid)
//        userService.deleteOne(currentUid)
//        return userStore
//    }
}