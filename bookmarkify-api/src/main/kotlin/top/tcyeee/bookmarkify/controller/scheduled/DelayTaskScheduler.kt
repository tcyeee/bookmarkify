package top.tcyeee.bookmarkify.controller.scheduled


import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * @author tcyeee
 * @date 4/16/25 11:12
 */
object DelayTaskScheduler {
    private val scheduler = Executors.newScheduledThreadPool(1)

    fun delayExecute(task: () -> Unit, delayMinutes: Long = 10, timeUnit: TimeUnit) {
        scheduler.schedule({ task() }, delayMinutes, timeUnit)
    }
}