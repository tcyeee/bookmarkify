package top.tcyeee.bookmarkify.config.event

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.async.AsyncConfig
import top.tcyeee.bookmarkify.server.ISimilarBookmarkService

/**
 * [SimilarColdComputeEvent] 的异步消费者。跑在 [AsyncConfig.BOOKMARK_ENRICH_EXECUTOR] 上：
 * 与富化同源的理由 —— 用户看不到、没人等结果、纯外部 IO，不该占用「加书签要转多久圈」的解析池。
 * 异常仅记日志（结果丢了下次 TTL 过期会重算）。
 */
@Component
class SimilarColdComputeListener(private val similarBookmarkService: ISimilarBookmarkService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.BOOKMARK_ENRICH_EXECUTOR)
    @EventListener
    fun onColdCompute(event: SimilarColdComputeEvent) = runCatching {
        similarBookmarkService.runColdCompute(event.siteId, event.title, event.description, event.host)
    }.onFailure { log.error("[Async] SimilarColdComputeEvent 处理失败: siteId={}", event.siteId, it) }.let { }
}
