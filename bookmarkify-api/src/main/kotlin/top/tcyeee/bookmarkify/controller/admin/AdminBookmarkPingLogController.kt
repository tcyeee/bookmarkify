package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.BookmarkPingLogSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkPingLogVO
import top.tcyeee.bookmarkify.entity.BookmarkSweepLogSearchParams
import top.tcyeee.bookmarkify.entity.SweepHealthVO
import top.tcyeee.bookmarkify.entity.SweepPreviewVO
import top.tcyeee.bookmarkify.entity.SweepTriggerParams
import top.tcyeee.bookmarkify.entity.SweepTriggerResultVO
import top.tcyeee.bookmarkify.entity.entity.SweepLogEntity
import top.tcyeee.bookmarkify.server.IBookmarkPingLogService
import top.tcyeee.bookmarkify.server.liveness.ILivenessSweepService
import top.tcyeee.bookmarkify.server.liveness.LivenessSweepService

@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/bookmark-ping-log")
class AdminBookmarkPingLogController(
    private val bookmarkPingLogService: IBookmarkPingLogService,
    private val livenessSweepService: ILivenessSweepService,
) {

    @PostMapping("/all")
    fun getAllLogs(@RequestBody params: BookmarkPingLogSearchParams): IPage<BookmarkPingLogVO> =
        bookmarkPingLogService.adminListAll(params)

    /**
     * 巡检轮次（一轮一行）。与 [getAllLogs] 是两个粒度：那边回答"这个域名最近怎么样"，
     * 这边回答"巡检系统本身怎么样"。传 `onlyBreaker=true` 直接筛出被熔断中止的轮次。
     */
    @PostMapping("/sweeps")
    fun getSweeps(@RequestBody params: BookmarkSweepLogSearchParams): IPage<SweepLogEntity> =
        bookmarkPingLogService.adminListSweeps(params)

    /**
     * 巡检健康摘要，后台的常驻告警条轮询它。
     *
     * 用 GET：这是一次纯读取，且要能被浏览器直接打开来排查。
     */
    @GetMapping("/sweep-health")
    fun getSweepHealth(@RequestParam(required = false, defaultValue = "24") windowHours: Int): SweepHealthVO =
        bookmarkPingLogService.adminSweepHealth(windowHours.coerceIn(1, 24 * 30))

    /**
     * 手动触发前的预览：这一轮会覆盖哪些书签、探几次、大概多久、会不会改判失联。
     *
     * 纯读取，用 GET。它与 [triggerSweep] 之间必然有时间差（游标会推进、站点活性会变），
     * 所以给出的是预估而非承诺 —— 具体口径见 `SweepPreviewVO`。
     */
    @GetMapping("/sweep-preview")
    fun previewSweep(@RequestParam taskLabel: String): SweepPreviewVO =
        livenessSweepService.sweepPreview(taskLabel)

    /**
     * 手动触发一轮巡检。
     *
     * ## 几件必须写在这里的事
     *
     * **执行体必须从代理上调。** 两个巡检方法都标了 `@Async`，走 [livenessSweepService] 这个注入进来的
     * 代理才会真的异步；在 Service 内部自调用会绕开代理，变成在 HTTP 线程上同步跑一轮 200 条探测。
     *
     * **[SweepPreviewVO.running] 的检查是尽力而为。** 这里读到"没在跑"之后、巡检真正 acquire 之前，
     * 另一个实例完全可能把锁抢走 —— 那种情况下这一轮会被巡检自己的 SETNX 挡下并打一行 warn，
     * 没有任何副作用。真正的互斥从来不在这里。
     *
     * **不受 `bookmarkify.scheduling.enabled` 影响。** 那个开关关的是 `ScheduledTasks` 这个
     * 定时入口（本地连着生产库时不该再自动跑一套），而手动触发是管理员的明示动作，
     * 两者是不同的问题；也正因如此，本地起服务时点这个按钮，跑的是生产库上的一轮真实巡检。
     */
    @PostMapping("/sweep-trigger")
    fun triggerSweep(@RequestBody params: SweepTriggerParams): SweepTriggerResultVO {
        // 先校验任务名，再看锁：预览拿不到 spec 会抛 E102，这里的语义要一致，
        // 不能让一个拼错的任务名走到"已受理"
        val preview = livenessSweepService.sweepPreview(params.taskLabel)
        if (preview.running) {
            return SweepTriggerResultVO(accepted = false, message = "上一轮「${params.taskLabel}」仍在进行，本次未触发")
        }
        when (params.taskLabel) {
            LivenessSweepService.TASK_LIVENESS_CHECK -> livenessSweepService.livenessCheckStaleBookmarks()
            LivenessSweepService.TASK_RETRY_UNREACHABLE -> livenessSweepService.retryUnreachableBookmarks()
            // sweepPreview 已经挡掉了未知任务名，走不到这里
            else -> throw CommonException(ErrorType.E102)
        }
        log.warn("[triggerSweep] 管理员手动触发巡检: taskLabel=${params.taskLabel}, 候选=${preview.candidates}, 预计探测=${preview.probes}")
        return SweepTriggerResultVO(
            accepted = true,
            message = "已触发，本轮预计探测 ${preview.probes} 条；完成后会出现在轮次列表里",
        )
    }
}
