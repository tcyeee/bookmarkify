package top.tcyeee.bookmarkify.controller

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
    @Description("每天凌晨2点执行")
    @Scheduled(cron = "0 0 2 * * ?")
    fun runTaskWithCron() {
        bookmarkService.checkAll()
    }
}