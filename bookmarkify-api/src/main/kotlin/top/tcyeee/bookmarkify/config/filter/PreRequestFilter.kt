package top.tcyeee.bookmarkify.config.filter

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.*
import jakarta.servlet.annotation.WebFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.result.ResultWrapper

/**
 * 请求过滤
 *
 * @author tcyeee
 * @date 2022/5/17 17:18
 */
@Order(1)
@Component
class PreRequestFilter(private val objectMapper: ObjectMapper) : Filter {
    private val log = LoggerFactory.getLogger(PreRequestFilter::class.java)

    @Throws(ServletException::class, IOException::class)
    override fun doFilter(
            request: ServletRequest,
            response: ServletResponse,
            filterChain: FilterChain
    ) {
        val http = request as HttpServletRequest
        val httpResp = response as HttpServletResponse
        val uri = http.requestURI
        val method = http.method
        val token = http.getHeader(TOKEN_HEADER)?.takeIf { it.isNotBlank() }

        val isWebSocket = "/ws".equals(uri, ignoreCase = true)
        val isOptions = method.equals("OPTIONS", ignoreCase = true)
        if (token == null || isOptions || isWebSocket) {
            filterChain.doFilter(request, response)
            return
        }

        if (isThrottled(token)) {
            log.warn("⛔ 请求过于频繁, token=$token, uri=$uri")
            httpResp.status = HttpStatus.TOO_MANY_REQUESTS.value()
            httpResp.contentType = "application/json;charset=UTF-8"
            httpResp.writer.apply {
                write(objectMapper.writeValueAsString(ResultWrapper.error(ErrorType.E107)))
                flush()
            }
            return
        }

        log.info("⛱ $method request to $uri")
        filterChain.doFilter(request, response)
    }

    /**
     * 周期性清理：丢弃过期时间戳，并把没有任何有效请求的 token 整条移除，
     * 避免 WINDOW_MAP 随轮换 token 无界增长（OOM 风险）。
     *
     * F-07: Uses ConcurrentHashMap.compute() for each key so that the drain-and-remove
     * is atomic with respect to concurrent isThrottled() calls on the same token.
     * The old synchronized(deque) approach had a window between iter.remove() and
     * the next computeIfAbsent() where two threads could obtain different deque
     * instances for the same token, splitting the rate-limit state across two locks.
     */
    @Scheduled(fixedRate = CLEANUP_INTERVAL_MILLIS)
    fun evictStaleEntries() {
        val now = System.currentTimeMillis()
        WINDOW_MAP.keys.toList().forEach { key ->
            WINDOW_MAP.compute(key) { _, deque ->
                if (deque == null) return@compute null
                while (true) {
                    val head = deque.peekFirst() ?: break
                    if (now - head > WINDOW_MILLIS) deque.pollFirst() else break
                }
                if (deque.isEmpty()) null else deque
            }
        }
    }

    private fun isThrottled(token: String): Boolean {
        val now = System.currentTimeMillis()
        var throttled = false
        // F-07: compute() holds a bucket-level lock for this key, making the
        // check-and-record atomic with evictStaleEntries()'s drain-and-remove.
        WINDOW_MAP.compute(token) { _, existing ->
            val deque = existing ?: ConcurrentLinkedDeque()
            while (deque.isNotEmpty() && now - deque.peekFirst()!! > WINDOW_MILLIS) {
                deque.pollFirst()
            }
            throttled = deque.size >= MAX_REQUESTS_PER_WINDOW
            if (!throttled) deque.addLast(now)
            deque
        }
        return throttled
    }

    companion object {
        private const val TOKEN_HEADER = "satoken"
        private const val WINDOW_MILLIS = 1000L
        private const val MAX_REQUESTS_PER_WINDOW = 20
        private const val CLEANUP_INTERVAL_MILLIS = 60_000L
        private val WINDOW_MAP: ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> =
                ConcurrentHashMap()
    }
}
