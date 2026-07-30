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

    /**
     * 书签元数据富化线程池（分类打标 / NSFW 判定，均为 DeepSeek 调用）。
     *
     * 与解析池分开的理由是这两类任务的**紧迫性完全不同**：解析池上跑的是「用户正盯着转圈等」的活，
     * 富化跑的是用户根本看不到的后台标注。混在一起时，一次批量导入的富化任务会把解析队列占满，
     * 直接体现为别的用户加书签变慢。
     */
    @Bean(BOOKMARK_ENRICH_EXECUTOR)
    fun bookmarkEnrichExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        // 纯外部 API 等待，且没人等结果，给少量线程慢慢消化即可
        corePoolSize = 2
        maxPoolSize = 8
        // 队列开得比解析池大得多：一次导入 2000 条书签会一次性排进来，而这里每条只是两次 HTTP 调用，
        // 排着慢慢做完全可以接受——真正要避免的是队列满了以后回退到调用线程，
        // 那个调用线程正是解析线程，等于把刚拆出去的耗时又还了回去
        queueCapacity = 10_000
        setThreadNamePrefix("bm-enrich-")
        setRejectedExecutionHandler { runnable, executor ->
            log.warn(
                "[bookmarkEnrichExecutor] 富化队列已满，任务回退到调用线程(解析线程)执行，加书签会变慢: " +
                    "active=${executor.activeCount}, queueSize=${executor.queue.size}"
            )
            ThreadPoolExecutor.CallerRunsPolicy().rejectedExecution(runnable, executor)
        }
        // 富化结果丢了不影响书签可用，停机时不必等它跑完
        setWaitForTasksToCompleteOnShutdown(false)
        initialize()
    }

    companion object {
        const val BOOKMARK_PARSE_EXECUTOR = "bookmarkParseExecutor"
        const val BOOKMARK_ENRICH_EXECUTOR = "bookmarkEnrichExecutor"
    }
}
