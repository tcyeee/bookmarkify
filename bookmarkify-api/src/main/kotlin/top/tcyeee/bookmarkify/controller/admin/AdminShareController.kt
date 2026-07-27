package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.ShareSearchParams
import top.tcyeee.bookmarkify.entity.UserShareAdminVO
import top.tcyeee.bookmarkify.server.IUserShareService

/**
 * 用户分享信息管理
 * @author tcyeee
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/share")
class AdminShareController(
    private val userShareService: IUserShareService,
) {

    @PostMapping("/all")
    fun all(@RequestBody params: ShareSearchParams): IPage<UserShareAdminVO> = userShareService.adminListAll(params)

    @PostMapping("/takedown")
    fun takedown(@RequestParam id: String): Boolean = userShareService.adminTakeDown(id)
}
