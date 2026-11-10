package top.tcyeee.bookmarkify.config.filter

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import top.tcyeee.bookmarkify.config.monitor.LogContext
import top.tcyeee.bookmarkify.utils.StpKit

/**
 * 把"这次请求是谁发的"写进 MDC。
 *
 * ## 为什么是拦截器而不是 Filter
 *
 * `PreRequestFilter` 跑在 DispatcherServlet **之前**，那时 Sa-Token 的上下文还没建立
 * （`SaTokenContextForSpringInJakartaServlet` 读的是 Spring 的 `RequestContextHolder`，
 * 它由 `FrameworkServlet.processRequest` 在更里层设置、返回前清除），所以在 Filter 里问
 * "当前登录的是谁"只会抛异常。拦截器注册在鉴权拦截器之后，这时登录态已经确定。
 *
 * 代价是未通过鉴权的请求没有这一项 —— 那是事实，不是缺陷：它们确实没有身份。
 *
 * ## 为什么值得做
 *
 * 在此之前，整套文本日志里**没有一行带用户标识**：一个用户报"我的书签一直在转圈"，
 * 没有任何办法把他的那几条请求从日志里挑出来。`requestId` 串得起一次请求的全部日志，
 * 但串不起"这个人的全部请求"。
 *
 * 写进 MDC 而不是只给完成日志用，是因为 [top.tcyeee.bookmarkify.config.async.MdcTaskDecorator]
 * 会把 MDC 透传进 `@Async` 线程池 —— 于是异步解析/富化/截图的日志也跟着带上触发它的那个用户。
 */
@Component
class MdcUserInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        actor()?.let {
            MDC.put(LogContext.UID_KEY, it)
            // 同一个值也挂在 request 上：请求完成日志在更外层的 Filter 里打，那时下面的
            // afterCompletion 已经把 MDC 清掉了。见 LogContext.ACTOR_ATTR
            LogContext.markActor(request, it)
        }
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        // Tomcat 线程池化复用，不清理会让上一个请求的 uid 泄漏进下一个不相关的请求
        // —— 同 PreRequestFilter 对 requestId 的处理
        MDC.remove(LogContext.UID_KEY)
    }

    /**
     * 两个 realm 都看，后台加 `admin:` 前缀。
     *
     * 前缀不是装饰：两个 realm 的会话完全独立，同一串 id 在 USER 与 ADMIN 下是两个主体，
     * 不加前缀的话"这条日志是管理员干的还是用户干的"在日志里就分不出来。
     */
    private fun actor(): String? = runCatching {
        when {
            StpKit.USER.isLogin -> StpKit.USER.loginIdAsString
            StpKit.ADMIN.isLogin -> "admin:${StpKit.ADMIN.loginIdAsString}"
            else -> null
        }
    }.getOrNull()
}
