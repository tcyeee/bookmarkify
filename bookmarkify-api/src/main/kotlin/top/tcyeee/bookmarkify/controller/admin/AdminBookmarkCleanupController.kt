package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.OrphanCleanupReport
import top.tcyeee.bookmarkify.server.repair.OrphanCleanupService

/**
 * 书签清理：删掉已经没有任何用户收藏、且再也不会有内容的页面与站点。
 *
 * 拆成 preview / run 两个接口而不是一个带 `dryRun` 参数的接口，是为了让"预览"在权限与调用
 * 意图上都明确是只读的——一个删除操作的开关藏在查询参数里，写错一次的代价没有撤销路径。
 * 两者背后是同一段判定代码，见 [OrphanCleanupService.run]。
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/bookmark-cleanup")
class AdminBookmarkCleanupController(
    private val orphanCleanupService: OrphanCleanupService,
) {

    @Operation(summary = "预览：这一轮清理会删掉什么(只统计，不写库)")
    @PostMapping("/preview")
    fun preview(): OrphanCleanupReport = orphanCleanupService.run(dryRun = true)

    @Operation(summary = "执行清理：删除无人引用的本地/IP 与已失活页面及其站点")
    @PostMapping("/run")
    fun run(): OrphanCleanupReport = orphanCleanupService.run(dryRun = false)
}
