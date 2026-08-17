package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.UserBehaviorLogSearchParams
import top.tcyeee.bookmarkify.entity.UserBehaviorLogVO
import top.tcyeee.bookmarkify.server.IUserBehaviorLogService

@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/user-behavior-log")
class AdminUserBehaviorLogController(
    private val userBehaviorLogService: IUserBehaviorLogService,
) {

    @PostMapping("/all")
    fun getAllLogs(@RequestBody params: UserBehaviorLogSearchParams): IPage<UserBehaviorLogVO> =
        userBehaviorLogService.adminListAll(params)
}
