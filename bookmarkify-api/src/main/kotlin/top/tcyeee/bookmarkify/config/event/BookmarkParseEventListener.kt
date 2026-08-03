package top.tcyeee.bookmarkify.config.event

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.async.AsyncConfig
import top.tcyeee.bookmarkify.server.IBookmarkService

/**
 * 书签异步解析事件监听器，取代原 KafkaMessageListener。
 *
 * 每个处理方法在 [AsyncConfig.BOOKMARK_PARSE_EXECUTOR] 线程池中执行；异常仅记录日志、不上抛
 * （与 Kafka 时期 auto-commit + 吞异常的行为一致），失败记录由 checkAll() 定时对账兜底重解析。
 */
@Component
class BookmarkParseEventListener(private val bookmarkService: IBookmarkService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.BOOKMARK_PARSE_EXECUTOR)
    @EventListener
    fun onParse(event: BookmarkParseEvent) = runCatching {
        bookmarkService.parseAndSave(event.bookmarkId)
    }.onFailure { log.error("[Async] BookmarkParseEvent 处理失败: bookmarkId={}", event.bookmarkId, it) }.let { }

    @Async(AsyncConfig.BOOKMARK_PARSE_EXECUTOR)
    @EventListener
    fun onParseAndNotice(event: BookmarkParseAndNoticeEvent) = runCatching {
        bookmarkService.parseAndNotice(event.uid, event.bookmarkId, event.userLinkId, event.nodeLayoutId)
    }.onFailure { log.error("[Async] BookmarkParseAndNoticeEvent 处理失败: bookmarkId={}", event.bookmarkId, it) }.let { }

    @Async(AsyncConfig.BOOKMARK_PARSE_EXECUTOR)
    @EventListener
    fun onParseAndResetUserItem(event: BookmarkParseAndResetUserItemEvent) = runCatching {
        bookmarkService.parseAndResetUserItem(event.uid, event.rawUrl, event.userLinkId, event.layoutNodeId)
    }.onFailure { log.error("[Async] BookmarkParseAndResetUserItemEvent 处理失败: rawUrl={}", event.rawUrl, it) }.let { }

    /** 跑在 [AsyncConfig.BOOKMARK_ENRICH_EXECUTOR] 而非解析池上：见 [BookmarkEnrichEvent]。 */
    @Async(AsyncConfig.BOOKMARK_ENRICH_EXECUTOR)
    @EventListener
    fun onEnrich(event: BookmarkEnrichEvent) = runCatching {
        bookmarkService.enrich(event.bookmarkId)
    }.onFailure { log.error("[Async] BookmarkEnrichEvent 处理失败: bookmarkId={}", event.bookmarkId, it) }.let { }

    /**
     * 跑在**单线程**的 [AsyncConfig.BOOKMARK_SCREENSHOT_EXECUTOR] 上：见 [BookmarkScreenshotEvent]。
     *
     * 失败只记 warn 不记 error —— 截图拿不到是常态（反爬站点、无头熔断、Chrome OOM），
     * 而它对书签可用性没有任何影响。用 error 会让日志里堆满不需要处理的噪音。
     */
    @Async(AsyncConfig.BOOKMARK_SCREENSHOT_EXECUTOR)
    @EventListener
    fun onScreenshot(event: BookmarkScreenshotEvent) = runCatching {
        bookmarkService.captureScreenshot(event.bookmarkId)
    }.onFailure { log.warn("[Async] 截图补抓失败(不影响书签): bookmarkId={}, err={}", event.bookmarkId, it.message) }.let { }
}
