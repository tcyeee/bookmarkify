package top.tcyeee.bookmarkify.config.event

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.async.AsyncConfig
import top.tcyeee.bookmarkify.server.IUserShareService

/**
 * 分享 AI 内容审核事件监听器：分享发布后异步跑一遍 AI 审核，发现违规会将其下架。
 *
 * 复用 [AsyncConfig.BOOKMARK_PARSE_EXECUTOR] 线程池；异常仅记录日志、不上抛。
 */
@Component
class ShareAiReviewEventListener(private val userShareService: IUserShareService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async(AsyncConfig.BOOKMARK_PARSE_EXECUTOR)
    @EventListener
    fun onReview(event: ShareAiReviewEvent) = runCatching {
        userShareService.performAiReview(event.shareId)
    }.onFailure { log.error("[Async] ShareAiReviewEvent 处理失败: shareId={}", event.shareId, it) }.let { }
}
