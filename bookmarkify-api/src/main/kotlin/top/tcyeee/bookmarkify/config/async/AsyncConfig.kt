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

    /**
     * 活性巡检线程池（`livenessCheckStaleBookmarks` / `retryUnreachableBookmarks` 的驱动线程）。
     *
     * 巡检此前跑在解析池上，与用户的 addOne 抢同一批线程和同一个队列：一轮 200 条串行 ping
     * 最坏要占着一个线程近一小时，期间用户加书签的任务只能排队，队列满了还会被
     * CallerRunsPolicy 甩回 HTTP 请求线程。`drainStuckLoading` 早就为这件事留了队列余量，
     * 巡检没理由不守同样的规矩。
     *
     * 只给 1 个核心线程 + 空队列 + AbortPolicy：两个巡检任务本来就该串行，
     * 拒绝也正是想要的行为——上一轮还没跑完就说明落后了，再排一个只会雪上加霜（[ParseLock]
     * 的巡检锁也会挡住它）。真正的并发发生在池内部的并行 ping（见 [BOOKMARK_PING_EXECUTOR]）。
     */
    @Bean(BOOKMARK_SWEEP_EXECUTOR)
    fun bookmarkSweepExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 1
        maxPoolSize = 2
        queueCapacity = 0
        setThreadNamePrefix("bm-sweep-")
        setRejectedExecutionHandler { _, executor ->
            log.warn(
                "[bookmarkSweepExecutor] 上一轮巡检仍在执行，本轮直接丢弃: " +
                    "active=${executor.activeCount}, poolSize=${executor.poolSize}"
            )
        }
        // 巡检中断了没有损失：下一轮按 next_check_at 会重新选中这些记录
        setWaitForTasksToCompleteOnShutdown(false)
        initialize()
    }

    /**
     * 巡检内部并行 ping 用的池。
     *
     * 串行 ping 的最坏耗时是「批量上限 × 单条超时」= 200 × 15s ≈ 50 分钟，已经贴着 1 小时的
     * 调度周期；并行到 8 路可压到 2 分钟量级。
     *
     * ⚠️ **[PING_CONCURRENCY] 是一个跨服务约束**：scrapper 用 tower 的 `concurrency_limit`
     * 为 `/scrape` + `/ping` 设了统一的并发上限（`MAX_CONCURRENT_REQUESTS`，默认 32），超出即
     * `load_shed` 返回 503。而 503 在我方会被判成 [PingOutcome.UNKNOWN]
     * [top.tcyeee.bookmarkify.entity.enums.PingOutcome]，占比过半就触发熔断——也就是说并发调太高
     * 会让整轮巡检自己把自己熔断掉。这里取 8，给用户触发的 `/scrape` 留足余量；
     * 改动 scrapper 那个上限时必须回头看这个值。
     */
    @Bean(BOOKMARK_PING_EXECUTOR)
    fun bookmarkPingExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = PING_CONCURRENCY
        maxPoolSize = PING_CONCURRENCY
        // 一轮最多投递 batchSize 条，队列开到足够容纳最大批次，避免退化成调用线程串行执行
        queueCapacity = 1_000
        setThreadNamePrefix("bm-ping-")
        // 队列都满了说明批次大小配得离谱，交给调用线程（巡检线程）兜着跑，慢但不丢
        setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
        setWaitForTasksToCompleteOnShutdown(false)
        initialize()
    }

    companion object {
        const val BOOKMARK_PARSE_EXECUTOR = "bookmarkParseExecutor"
        const val BOOKMARK_ENRICH_EXECUTOR = "bookmarkEnrichExecutor"
        const val BOOKMARK_SWEEP_EXECUTOR = "bookmarkSweepExecutor"
        const val BOOKMARK_PING_EXECUTOR = "bookmarkPingExecutor"

        /** 并行 ping 的并发度。调整前先读 [bookmarkPingExecutor] 上关于 scrapper 并发上限的说明。 */
        const val PING_CONCURRENCY = 8
    }
}
