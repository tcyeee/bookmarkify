package top.tcyeee.bookmarkify.server.log

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.server.IAdminActionLogService
import top.tcyeee.bookmarkify.server.IAiCallLogService
import top.tcyeee.bookmarkify.server.IScrapperCallLogService
import top.tcyeee.bookmarkify.server.IUserBehaviorLogService

/**
 * 日志表保留期的执行者：一轮跑完四张表，打一行汇总。
 *
 * 分出这么一个类而不是把四次调用塞进 `ScheduledTasks`，是为了让"清理了什么"有一个统一的出口：
 * 每张表各打一行 warn 的话，正常运行时每天就有四行噪音，而真正值得注意的是**总量突然变大**
 * （某张表开始爆炸式增长）。一行汇总看得出这件事，四行分散的日志看不出。
 *
 * 每张表各自包 `runCatching`：一张表的清理失败（比如列还没迁移）不该让后面三张跟着不跑。
 */
@Service
class LogRetentionService(
    private val scrapperCallLogService: IScrapperCallLogService,
    private val aiCallLogService: IAiCallLogService,
    private val userBehaviorLogService: IUserBehaviorLogService,
    private val adminActionLogService: IAdminActionLogService,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun purgeAll() {
        val results = linkedMapOf(
            "scrapper_call_log" to runSafely("scrapper_call_log") { scrapperCallLogService.purgeExpired() },
            "ai_call_log" to runSafely("ai_call_log") { aiCallLogService.purgeExpired() },
            "user_behavior_log" to runSafely("user_behavior_log") { userBehaviorLogService.purgeExpired() },
            "admin_action_log" to runSafely("admin_action_log") { adminActionLogService.purgeExpired() },
        )
        val total = results.values.sum()
        if (total > 0) logger.warn("[LogRetention] 本轮清理 {} 行: {}", total, results.filterValues { it > 0 })
    }

    private fun runSafely(table: String, block: () -> Int): Int = runCatching(block).getOrElse {
        logger.warn("[LogRetention] {} 清理失败(忽略，下一轮再试): {}", table, it.message)
        0
    }
}
