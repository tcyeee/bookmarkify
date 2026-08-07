package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.ConfigChangeLogSearchParams
import top.tcyeee.bookmarkify.entity.ConfigChangeLogVO
import top.tcyeee.bookmarkify.server.IConfigChangeLogService

/** 系统配置变更记录（只读；写入在 JsonConfigAccessor.update） */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/config-change-log")
class AdminConfigChangeLogController(
    private val configChangeLogService: IConfigChangeLogService,
) {
    @PostMapping("/all")
    fun getAllLogs(@RequestBody params: ConfigChangeLogSearchParams): IPage<ConfigChangeLogVO> =
        configChangeLogService.adminListAll(params)
}
