package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.BookmarkPingLogSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkPingLogVO
import top.tcyeee.bookmarkify.entity.BookmarkSweepLogSearchParams
import top.tcyeee.bookmarkify.entity.SweepHealthVO
import top.tcyeee.bookmarkify.entity.entity.BookmarkSweepLogEntity
import top.tcyeee.bookmarkify.server.IBookmarkPingLogService

@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/bookmark-ping-log")
class AdminBookmarkPingLogController(
    private val bookmarkPingLogService: IBookmarkPingLogService,
) {

    @PostMapping("/all")
    fun getAllLogs(@RequestBody params: BookmarkPingLogSearchParams): IPage<BookmarkPingLogVO> =
        bookmarkPingLogService.adminListAll(params)

    /**
     * 巡检轮次（一轮一行）。与 [getAllLogs] 是两个粒度：那边回答"这个域名最近怎么样"，
     * 这边回答"巡检系统本身怎么样"。传 `onlyBreaker=true` 直接筛出被熔断中止的轮次。
     */
    @PostMapping("/sweeps")
    fun getSweeps(@RequestBody params: BookmarkSweepLogSearchParams): IPage<BookmarkSweepLogEntity> =
        bookmarkPingLogService.adminListSweeps(params)

    /**
     * 巡检健康摘要，后台的常驻告警条轮询它。
     *
     * 用 GET：这是一次纯读取，且要能被浏览器直接打开来排查。
     */
    @GetMapping("/sweep-health")
    fun getSweepHealth(@RequestParam(required = false, defaultValue = "24") windowHours: Int): SweepHealthVO =
        bookmarkPingLogService.adminSweepHealth(windowHours.coerceIn(1, 24 * 30))
}
