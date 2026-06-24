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
}