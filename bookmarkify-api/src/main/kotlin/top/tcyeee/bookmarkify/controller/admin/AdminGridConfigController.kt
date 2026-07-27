package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.entity.AdminGridConfigSaveParams
import top.tcyeee.bookmarkify.entity.AdminGridConfigVO
import top.tcyeee.bookmarkify.server.IAdminGridConfigService
import top.tcyeee.bookmarkify.utils.StpKit

/** 后台表格自定义列配置（宽度/显隐/排序），按当前管理员账号隔离 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/grid-config")
class AdminGridConfigController(
    private val adminGridConfigService: IAdminGridConfigService,
) {
    @PostMapping("/{gridId}")
    fun query(@PathVariable gridId: String): AdminGridConfigVO =
        adminGridConfigService.queryVO(StpKit.ADMIN.loginIdAsString, gridId)

    @PostMapping("/{gridId}/save")
    fun save(@PathVariable gridId: String, @RequestBody params: AdminGridConfigSaveParams): Boolean =
        adminGridConfigService.upsert(StpKit.ADMIN.loginIdAsString, gridId, params)
}
