package top.tcyeee.bookmarkify.config.filter

import jakarta.servlet.*
import jakarta.servlet.annotation.WebFilter
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.io.IOException

/**
 * 请求过滤
 *
 * @author tcyeee
 * @date 2022/5/17 17:18
 */
@Order(1)
@Component
@WebFilter(filterName = "requestFilter", urlPatterns = ["/*"])
class PreRequestFilter : Filter {
    private var log = LoggerFactory.getLogger(PreRequestFilter::class.java)

    @Throws(ServletException::class, IOException::class)
    override fun doFilter(request: ServletRequest, response: ServletResponse, filterChain: FilterChain) {
        val http = request as HttpServletRequest
        val isWebSocket = "/ws".equals(http.requestURI, ignoreCase = true)
        val isOptions = http.method.equals("OPTIONS", ignoreCase = true)
        if (!isOptions && !isWebSocket) log.info("⛱ send ${http.method} request to ${http.requestURI}")
        filterChain.doFilter(request, response)
    }
}
