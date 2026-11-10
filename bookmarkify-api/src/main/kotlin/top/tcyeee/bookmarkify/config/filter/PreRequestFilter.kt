package top.tcyeee.bookmarkify.config.filter

import cn.hutool.core.util.IdUtil
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.*
import jakarta.servlet.annotation.WebFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.monitor.LogContext
import top.tcyeee.bookmarkify.config.result.ResultWrapper
import top.tcyeee.bookmarkify.utils.LogSafe

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

        // 短 requestId：串联一次 HTTP 请求从 controller 到 @Async 线程池（跨线程传递见
        // MdcTaskDecorator）再到 WebSocket 推送的全部日志。放在最外层、早于下面所有的
        // 提前返回分支，这样限流、未登录这些路径也带得上。finally 里清空——Tomcat 线程
        // 是池化复用的，不清理会让这次请求的 id 泄漏进下一个不相关的请求。
        val requestId = IdUtil.fastSimpleUUID().take(8)
        MDC.put(LogContext.REQUEST_ID_KEY, requestId)
        httpResp.setHeader(LogContext.REQUEST_ID_HEADER, requestId)
        val startedAt = System.currentTimeMillis()
        try {
            val isWebSocket = "/ws".equals(uri, ignoreCase = true)
            val isOptions = method.equals("OPTIONS", ignoreCase = true)
            if (token == null || isOptions || isWebSocket) {
                filterChain.doFilter(request, response)
                return
            }

            if (isThrottled(token)) {
                // token 打码：它是一把 30 天有效的钥匙，整串落进日志等于把凭据写在磁盘上，
                // 而排查限流只需要分得清"是不是同一个会话在刷"。见 LogSafe.maskToken
                log.warn("⛔ 请求过于频繁, token=${LogSafe.maskToken(token)}, uri=$uri")
                httpResp.status = HttpStatus.TOO_MANY_REQUESTS.value()
                httpResp.contentType = "application/json;charset=UTF-8"
                httpResp.writer.apply {
                    write(objectMapper.writeValueAsString(ResultWrapper.error(ErrorType.E107)))
                    flush()
                }
                return
            }

            filterChain.doFilter(request, response)
        } finally {
            // 请求**完成**日志。从前这里只有一行入口日志（`⛱ GET request to /x`），它缺三样
            // 东西：耗时、成败、谁发的；而且它在 `token == null` 的分支之后，于是登录、
            // 网站反馈提交、插件接口（走 X-Extension-Token）这些请求在文本日志里一行都没有。
            //
            // 判成败只能看业务码：本项目用 HTTP 200 承载业务错误，线上实测连不存在的路径都回
            // 200（`{"ok":false,"code":101}`），HTTP 状态码这一维是个常量 —— 这也是为什么
            // "哪个接口在变慢 / 报错率高"此前没有任何数据源能回答。见 LogContext.BIZ_CODE_ATTR
            logCompletion(http, httpResp, startedAt)
            MDC.remove(LogContext.REQUEST_ID_KEY)
        }
    }

    /**
     * 一行请求完成日志：`method uri -> http/业务码 cost=..ms uid=..`。
     *
     * 级别按结果分三档，目的是让"值得看的那几行"能被 grep 出来，而不是淹在成功日志里：
     * - 业务码非 0（除未登录 E101，那是前端常态轮询）或 HTTP ≥ 400 → `warn`
     * - 耗时超过 [SLOW_MILLIS] → `warn`，附 `slow` 标记
     * - 其余 → `info`
     *
     * 刻意不打请求体：那是另一个量级的数据，且必然包含用户内容（书签标题、备注）。
     */
    private fun logCompletion(http: HttpServletRequest, resp: HttpServletResponse, startedAt: Long) {
        val cost = System.currentTimeMillis() - startedAt
        val uri = http.requestURI + (http.queryString?.let { "?$it" } ?: "")
        // 健康检查按分钟被容器与 CI 轮询 —— 一天 1440 行"一切正常"，是纯噪音，而这次改动的目的
        // 恰恰是让日志更值得读。降到 debug：真出问题时 /internal/health 自己会打一条 warn
        if (http.requestURI.startsWith(HEALTH_PATH)) {
            log.debug("⛱ ${http.method} $uri -> ${resp.status} cost=${cost}ms")
            return
        }
        val bizCode = http.getAttribute(LogContext.BIZ_CODE_ATTR) as? Int
        val actor = LogContext.actorOf(http) ?: "-"
        val slow = cost >= SLOW_MILLIS
        val failed = resp.status >= 400 || (bizCode != null && bizCode != 0 && bizCode != NOT_LOGIN_CODE)
        val line = "⛱ ${http.method} $uri -> ${resp.status}/${bizCode ?: "-"} cost=${cost}ms uid=$actor" +
            if (slow) " slow" else ""
        if (failed || slow) log.warn(line) else log.info(line)
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

        /** 健康检查路径前缀，见下面 logCompletion 里的降噪处理 */
        private const val HEALTH_PATH = "/internal/health"

        /** 慢请求阈值。超过它的请求按 warn 打出来，否则"变慢"这件事在 info 的洪流里看不见 */
        private const val SLOW_MILLIS = 1500L

        /**
         * 未登录（E101）不算失败。
         *
         * 前端在 token 过期后仍会继续发几个请求，桌面首屏也会先探一次登录态 —— 把它算成失败会让
         * 完成日志里最常见的一行变成 warn，等于把这个级别用废。真正的未登录现场另有一条
         * `[未登录]` 日志，带 referer / token 来源 / 失败原因。
         */
        private const val NOT_LOGIN_CODE = 101
        private const val WINDOW_MILLIS = 1000L
        private const val MAX_REQUESTS_PER_WINDOW = 20
        private const val CLEANUP_INTERVAL_MILLIS = 60_000L
        private val WINDOW_MAP: ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> =
                ConcurrentHashMap()
    }
}
