package top.tcyeee.bookmarkify.config.async

import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator

/**
 * 把提交任务那一刻的 MDC（主要是 [top.tcyeee.bookmarkify.config.filter.PreRequestFilter]
 * 生成的 requestId）复制到线程池的执行线程。
 *
 * `ThreadPoolTaskExecutor` 默认不会跨线程传递 MDC——一进 [AsyncConfig] 的任何一个池子，
 * 日志就会丢失 requestId，没法把一次 HTTP 请求触发的异步解析/富化/截图日志和该请求关联起来。
 * 执行完必须把执行线程的 MDC 恢复成提交前的样子：池子线程是复用的，不清理会让上一个任务的
 * requestId 泄漏进下一个不相关的任务。
 */
class MdcTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val context = MDC.getCopyOfContextMap()
        return Runnable {
            val previous = MDC.getCopyOfContextMap()
            try {
                if (context != null) MDC.setContextMap(context) else MDC.clear()
                runnable.run()
            } finally {
                if (previous != null) MDC.setContextMap(previous) else MDC.clear()
            }
        }
    }
}
