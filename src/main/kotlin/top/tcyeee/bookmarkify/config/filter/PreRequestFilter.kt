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
        val httpRequest = request as HttpServletRequest
        log.info(String.format("⛱ send %s request to %s", httpRequest.method, httpRequest.requestURI))
        filterChain.doFilter(request, response)
    }
}
