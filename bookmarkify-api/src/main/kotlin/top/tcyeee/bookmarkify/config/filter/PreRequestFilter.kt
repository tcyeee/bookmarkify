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
@WebFilter(filterName = "requestFilter", urlPatterns = ["/*"])
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
            if (!isOptions && !isWebSocket) log.info("⛱ send $method request to $uri")
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

        log.info("⛱ send $method request to $uri")
        filterChain.doFilter(request, response)
    }

    private fun isThrottled(token: String): Boolean {
        val now = System.currentTimeMillis()
        val deque = WINDOW_MAP.computeIfAbsent(token) { ConcurrentLinkedDeque() }
        synchronized(deque) {
            while (true) {
                val head = deque.peekFirst() ?: break
                if (now - head > WINDOW_MILLIS) deque.pollFirst() else break
            }
            if (deque.size >= MAX_REQUESTS_PER_WINDOW) return true
            deque.addLast(now)
        }
        return false
    }

    companion object {
        private const val TOKEN_HEADER = "satoken"
        private const val WINDOW_MILLIS = 1000L
        private const val MAX_REQUESTS_PER_WINDOW = 20
        private val WINDOW_MAP: ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> =
                ConcurrentHashMap()
    }
}
