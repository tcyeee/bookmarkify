package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.entity.BookmarkLivenessConfigUpdateParams
import top.tcyeee.bookmarkify.entity.BookmarkLivenessConfigVO
import top.tcyeee.bookmarkify.server.IBookmarkLivenessConfigService

/** 书签活性检查频率配置（全局，影响定时任务 ScheduledTasks 的检测频率） */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/bookmark-liveness-config")
class AdminBookmarkLivenessConfigController(
    private val bookmarkLivenessConfigService: IBookmarkLivenessConfigService,
) {
    @PostMapping
    fun query(): BookmarkLivenessConfigVO = BookmarkLivenessConfigVO(bookmarkLivenessConfigService.getConfig())

    @PostMapping("/save")
    fun save(@RequestBody params: BookmarkLivenessConfigUpdateParams): BookmarkLivenessConfigVO =
        BookmarkLivenessConfigVO(
            bookmarkLivenessConfigService.updateConfig(
                params.activeCheckIntervalHours,
                params.abnormalCheckIntervalHours,
            )
        )
}
