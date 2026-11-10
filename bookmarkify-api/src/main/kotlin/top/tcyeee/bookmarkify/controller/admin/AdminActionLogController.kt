package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.AdminActionLogSearchParams
import top.tcyeee.bookmarkify.entity.entity.AdminActionLogEntity
import top.tcyeee.bookmarkify.server.IAdminActionLogService

/**
 * 后台操作审计的读取端（系统管理 › 操作审计）。
 *
 * 实体直接当响应用，不另造 VO：字段完全一致，而手写一层映射在这种情况下只会多一个"漏掉一行
 * 就把那个字段永久钉死在默认值"的失败点（同 `BookmarkLivenessConfigValue` 的理由）。
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/action-log")
class AdminActionLogController(
    private val adminActionLogService: IAdminActionLogService,
) {

    @PostMapping("/all")
    fun getAllLogs(@RequestBody params: AdminActionLogSearchParams): IPage<AdminActionLogEntity> =
        adminActionLogService.adminListAll(params)
}
