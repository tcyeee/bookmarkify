package top.tcyeee.bookmarkify.controller.scheduled

import org.springframework.context.annotation.Description
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.server.IBookmarkService

/**
 * @author tcyeee
 * @date 3/14/25 20:34
 */
@Component
class ScheduledTasks(
    private val bookmarkService: IBookmarkService
) {
    @Description("每5分钟对账一次未完成解析的书签")
    @Scheduled(cron = "0 */5 * * * ?")
    fun runTaskWithCron() = bookmarkService.checkAll()

    @Description("每小时重试 UNREACHABLE 书签中已超过「异常书签检测频率」配置的部分：ping 通则触发重新解析，并写入 bookmark_ping_log")
    @Scheduled(cron = "0 0 * * * ?")
    fun retryUnreachableBookmarks() = bookmarkService.retryUnreachableBookmarks()

    @Description("每小时扫描一次超过「已激活书签检测频率」配置未更新的书签（含已认证）做活性检查，并写入 bookmark_ping_log")
    @Scheduled(cron = "0 0 * * * ?")
    fun livenessCheckStaleBookmarks() = bookmarkService.livenessCheckStaleBookmarks()
}