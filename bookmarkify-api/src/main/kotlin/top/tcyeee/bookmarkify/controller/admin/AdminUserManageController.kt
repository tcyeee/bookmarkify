package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.UserAdminVO
import top.tcyeee.bookmarkify.entity.UserSearchParams
import top.tcyeee.bookmarkify.server.IUserService

/**
 * @author tcyeee
 * @date 1/8/26 16:49
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/user")
class AdminUserManageController(
    private val userService: IUserService,
) {

    @PostMapping("/all")
    fun getAllUsers(@RequestBody params: UserSearchParams): IPage<UserAdminVO> =
        userService.adminListAll(params)
}
