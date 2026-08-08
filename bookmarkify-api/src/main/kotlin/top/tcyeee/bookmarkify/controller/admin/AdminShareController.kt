package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.ShareAdminDetailVO
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

    /**
     * 单条分享的详情(含其包含的全部书签)。
     *
     * 分享不存在时返回 `data: null` 而**不是** HTTP 404 —— 全站走 `ResultWrapper` 信封，
     * 空 body 会被一律包成 `ok=true`，状态码与信封说法相反，前端两种判法各错一半。
     */
    @GetMapping("/detail")
    fun detail(@RequestParam id: String): ShareAdminDetailVO? = userShareService.adminDetail(id)

    @PostMapping("/takedown")
    fun takedown(@RequestParam id: String): Boolean = userShareService.adminTakeDown(id)
}
