package top.tcyeee.bookmarkify.config.async

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import top.tcyeee.bookmarkify.config.log
import java.util.concurrent.ThreadPoolExecutor

/**
 * 异步任务线程池配置。
 *
 * 用于书签解析这类「慢 IO（抓取/第三方 API/OSS 上传）+ 不阻塞请求线程」的后台任务，
 * 取代原先借助 Kafka 在同一 JVM 内自产自销实现的异步派发。
 *
 * 失败兜底依旧由 BookmarkServiceImpl.checkAll() 定时对账负责（与 Kafka 时期一致：
 * 当时 enable-auto-commit=true 且监听器吞掉异常，本就没有重投/DLQ）。
 */
@Configuration
@EnableAsync
class AsyncConfig {

    /** 书签解析异步线程池 */
    @Bean(BOOKMARK_PARSE_EXECUTOR)
    fun bookmarkParseExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        // 任务瓶颈在网络 IO（ping/scrapper 最长 60s、DeepSeek 10s、OSS 上传），不是 CPU，
        // 多开线程成本很低；把上限抬高能显著降低「队列被打满、退化到调用线程同步执行」的概率——
        // 调用线程往往就是触发 addOne/导入的 HTTP 请求线程，一旦退化会让用户的请求被迫卡住整段抓取耗时。
        corePoolSize = 8
        maxPoolSize = 32
        // 有界队列提供背压：批量导入会逐条投递，队列满后由调用线程兜底执行，避免无限堆积 OOM
        queueCapacity = 500
        setThreadNamePrefix("bm-parse-")
        setRejectedExecutionHandler { runnable, executor ->
            // 线程池 + 队列都已饱和才会走到这里：CallerRunsPolicy 会让调用线程同步跑完这个任务
            // （可能是几十秒的网络调用）。先打一条告警留痕，便于运维发现「加书签卡住」是这里导致的，
            // 而不是等用户反馈才排查；实际兜底行为仍委托给标准 CallerRunsPolicy。
            log.warn(
                "[bookmarkParseExecutor] 线程池与队列已饱和，任务将回退到调用线程同步执行: " +
                    "active=${executor.activeCount}, poolSize=${executor.poolSize}, queueSize=${executor.queue.size}"
            )
            ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(runnable, executor)
        }
        // 优雅停机：等待在途解析任务执行完再退出
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }

    companion object {
        const val BOOKMARK_PARSE_EXECUTOR = "bookmarkParseExecutor"
    }
}
