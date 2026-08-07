package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue
import top.tcyeee.bookmarkify.server.IBookmarkLivenessConfigService

/**
 * 书签活性检查频率配置（全局，影响定时任务 ScheduledTasks 的检测频率）。
 *
 * 出入参都是 [BookmarkLivenessConfigValue] 本身：三者结构一致，中间那两层只是抄字段。
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/bookmark-liveness-config")
class AdminBookmarkLivenessConfigController(
    private val bookmarkLivenessConfigService: IBookmarkLivenessConfigService,
) {
    @PostMapping
    fun query(): BookmarkLivenessConfigValue = bookmarkLivenessConfigService.getConfig()

    @PostMapping("/save")
    fun save(@RequestBody params: BookmarkLivenessConfigValue): BookmarkLivenessConfigValue =
        bookmarkLivenessConfigService.updateConfig(params)
}
