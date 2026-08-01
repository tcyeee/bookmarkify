package top.tcyeee.bookmarkify.controller.scheduled

import org.springframework.context.annotation.Description
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.server.IBookmarkPingLogService
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IOssReconcileService

/**
 * @author tcyeee
 * @date 3/14/25 20:34
 */
@Component
class ScheduledTasks(
    private val bookmarkService: IBookmarkService,
    private val bookmarkPingLogService: IBookmarkPingLogService,
    private val ossReconcileService: IOssReconcileService,
) {
    @Description("每5分钟对账一次未完成解析的书签")
    @Scheduled(cron = "0 */5 * * * ?")
    fun runTaskWithCron() = bookmarkService.checkAll()

    @Description(
        "每30秒把桌面上还在转圈的书签补投递给解析线程池。" +
            "批量导入只落库不投递事件，全靠这个任务按线程池余量分批消费，所以间隔要短——" +
            "它决定了用户导入后多久能看到第一批结果。"
    )
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    fun drainStuckLoading() = bookmarkService.drainStuckLoading()

    /**
     * 与 [livenessCheckStaleBookmarks] 刻意错开半小时。
     *
     * 两个巡检原本都在整点触发，同一秒一起打 scrapper——而 scrapper 对 `/scrape` + `/ping`
     * 有统一的并发上限，超出即 503，被我方判成「探测无结论」，占比过半还会触发熔断。
     * 错开之后两轮各自独占一段时间窗，也不再和整点的其他定时任务挤在一起。
     */
    @Description("每小时(半点)重试到期的 UNREACHABLE 书签：ping 通则触发重新抓取，结果写入 bookmark_ping_log")
    @Scheduled(cron = "0 30 * * * ?")
    fun retryUnreachableBookmarks() = bookmarkService.retryUnreachableBookmarks()

    @Description("每小时(整点)巡检到期的 SUCCESS 书签：探测存活情况并按「内容重新抓取间隔」刷新内容，结果写入 bookmark_ping_log")
    @Scheduled(cron = "0 0 * * * ?")
    fun livenessCheckStaleBookmarks() = bookmarkService.livenessCheckStaleBookmarks()

    @Description("每天凌晨 3 点清理过期的活性探测日志，避免这张只增不减的表无限膨胀")
    @Scheduled(cron = "0 0 3 * * ?")
    fun purgeExpiredPingLogs() = bookmarkPingLogService.purgeExpired()

    /**
     * 放在凌晨 4 点：要把桶里几万个 key 全部列一遍并与库比对，与白天的抓取、巡检错开。
     *
     * 频率取每天一次而不是每小时 —— 这是**盘点**不是巡检，孤儿对象多存一天没有任何代价，
     * 而每小时列一次全桶要付实打实的 ListObjects 调用费。
     *
     * 是否真的回收由 `bookmarkify.oss.reclaim-orphans` 决定，默认只报不删。
     */
    @Description("每天凌晨 4 点对账 OSS：桶↔账本↔引用方三方比对，标记孤儿对象")
    @Scheduled(cron = "0 0 4 * * ?")
    fun reconcileOssObjects() {
        ossReconcileService.reconcile()
    }
}