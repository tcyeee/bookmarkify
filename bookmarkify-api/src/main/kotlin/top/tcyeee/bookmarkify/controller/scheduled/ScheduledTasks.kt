package top.tcyeee.bookmarkify.controller.scheduled

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Description
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.server.IBookmarkPingLogService
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IOssReconcileService
import top.tcyeee.bookmarkify.server.liveness.ILivenessSweepService
import top.tcyeee.bookmarkify.server.asset.SiteAssetWriter

/**
 * 全部后台定时任务。
 *
 * ## 为什么整个类挂了一个开关
 *
 * 这里每一个任务都**直接写生产数据**：投递解析事件、改 `parse_status`、删日志行、
 * 甚至回收 OSS 对象。而 `application-dev.yml` 连的就是生产库（见那个文件的说明），
 * 于是本地随手 `bootRun` 一下，笔记本上就会多出一整套定时任务，与线上实例并行地
 * 往同一个库上写 —— 两边各跑一遍 ping、各投一遍解析、各删一遍日志。
 *
 * 这不是假想：2026-08-06 线上实测到 `bookmark_sweep_log` 同一轮出现两行，相隔 128ms，
 * 追下去是一台开发机（`cy.local`）上的实例连着生产库在跑巡检，而且它还抢走了
 * [SingleInstanceGuard][top.tcyeee.bookmarkify.config.init.SingleInstanceGuard] 的租约。
 * 那张表刚建好就把这件事照了出来。
 *
 * 所以：**定时任务只在线上跑**。`bookmarkify.scheduling.enabled` 默认 true，
 * 由 `application-dev.yml` 显式置 false。
 *
 * ⚠️ 本地关掉之后，`drainStuckLoading` 也不跑了 —— 而那是批量导入的**唯一**消费通道。
 * 本地测导入时导入的书签会一直停在转圈状态，这是预期行为，不是 bug。真要在本地测那条
 * 链路，用一套本地数据库并显式打开：`--bookmarkify.scheduling.enabled=true`。
 *
 * @author tcyeee
 * @date 3/14/25 20:34
 */
@Component
@ConditionalOnProperty(
    prefix = "bookmarkify.scheduling",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class ScheduledTasks(
    private val bookmarkService: IBookmarkService,
    private val bookmarkPingLogService: IBookmarkPingLogService,
    private val ossReconcileService: IOssReconcileService,
    private val livenessSweepService: ILivenessSweepService,
    private val siteAssetWriter: SiteAssetWriter,
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
    fun retryUnreachableBookmarks() = livenessSweepService.retryUnreachableBookmarks()

    @Description("每小时(整点)巡检到期的 SUCCESS 书签：探测存活情况并按「内容重新抓取间隔」刷新内容，结果写入 bookmark_ping_log")
    @Scheduled(cron = "0 0 * * * ?")
    fun livenessCheckStaleBookmarks() = livenessSweepService.livenessCheckStaleBookmarks()

    // 「每天凌晨 2 点复活探测已归档书签」这条任务已移除。归档现在是终态：它由管理员配置的
    // 「失活网站最大重试次数」触发，到达即彻底停止巡检。出口改为按需——有新用户添加该网址时
    // 就地重置重试次数并重新检查（BookmarkServiceImpl.reviveOnAdd）。定时复活的成本是永久的
    // （再也不会回来的域名每 30 天照样吃一次探测和一个 LIMIT 名额），收益却随时间趋近于零。

    @Description("每天凌晨 3 点清理过期的活性探测日志，避免这张只增不减的表无限膨胀")
    @Scheduled(cron = "0 0 3 * * ?")
    fun purgeExpiredPingLogs() = bookmarkPingLogService.purgeExpired()

    /**
     * 与上面的日志清理错开 15 分钟：两者都是删行，但这一条要跑窗口函数扫全表，
     * 没必要和它抢同一秒的 IO。
     */
    @Description("每天凌晨 3:15 收敛抓取快照：每个书签只留最近 3 份，其余删除")
    @Scheduled(cron = "0 15 3 * * ?")
    fun purgeOldScrapeSnapshots() {
        siteAssetWriter.purgeOldSnapshots()
    }

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