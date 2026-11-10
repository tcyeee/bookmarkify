package top.tcyeee.bookmarkify.config.monitor

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.MDC

/**
 * 一次请求在日志体系里的**身份**，以及把它取出来的唯一入口。
 *
 * ## 为什么需要这个对象
 *
 * 这套系统的日志实际是两层：文本日志（logback，带 `requestId`）与七张领域账本
 * （`ai_call_log` / `scrapper_call_log` / `user_behavior_log` / `admin_action_log` …）。
 * 从前两层之间**没有任何共同的键**：拿到一条失败的 `scrapper_call_log` 行，没办法找到同一次
 * 请求产生的那几十行文本日志；反过来也一样。于是排查永远只能看一半现场。
 *
 * 唯一的例外是 `page_ping_log.sweep_id` —— 它证明了这个关联键在本项目里是有用的，
 * 只是从前只在那一处做了。现在的做法是把 `requestId` 下沉成所有账本的默认列（见各
 * `*LogEntity` 的 `requestId` 默认值），而它的取值来源就是这里。
 *
 * [REQUEST_ID_KEY] 以前是 `PreRequestFilter` 的私有常量，账本侧够不到；
 * 提到这里是为了让"写 MDC 的人"和"读 MDC 的人"引用同一个字面量。
 */
object LogContext {

    /** MDC 键：短 requestId，由 `PreRequestFilter` 对每个 HTTP 请求生成，logback pattern 里打出来 */
    const val REQUEST_ID_KEY = "requestId"

    /**
     * MDC 键：当前登录者（普通用户为 uid，后台为 `admin:<id>`）。
     *
     * 由 `MdcUserInterceptor` 在鉴权通过之后写入 —— 必须等到那时才拿得到，Sa-Token 的上下文
     * 在 Filter 阶段还没建立。请求完成日志把它打出来，于是"是哪个用户触发的"第一次成为
     * 文本日志里可以 grep 的东西。
     */
    const val UID_KEY = "uid"

    /** 回给客户端的响应头。前端在报错提示里展示它，用户报障时这一串就是定位凭据 */
    const val REQUEST_ID_HEADER = "X-Request-Id"

    /**
     * request attribute 键：本次响应的**业务**错误码。
     *
     * 本项目用 HTTP 200 承载业务错误（`ResultWrapper{ok,code,msg}`），线上实测任何路径
     * （包括根本不存在的路径）都返回 200 —— 也就是说 HTTP 状态码这一维在本系统里是个常量，
     * nginx 的 access log 也救不了。请求完成日志要分得清成败，只能靠这个值，而它只在
     * `GlobalExceptionHandler`（两条出口：异常分支与响应包装分支）里才看得到。
     */
    const val BIZ_CODE_ATTR = "bookmarkify.bizCode"

    /**
     * request attribute 键：本次请求的登录者。
     *
     * 为什么不能只靠 MDC：拦截器的 `afterCompletion` 跑在 DispatcherServlet 里，而请求完成日志
     * 在**更外层**的 Filter 的 finally 里打 —— 也就是说 MDC 里的 uid 那时**已经被清掉了**，
     * 完成日志拿到的永远是空。而清理不能省：Tomcat 线程池化复用，不清会把这次的 uid 泄漏给
     * 下一个不相关的请求。于是同一个值写两处：MDC 给业务日志与异步线程池，attribute 给完成日志。
     */
    const val ACTOR_ATTR = "bookmarkify.actor"

    /** 当前线程的 requestId；定时任务等非 HTTP 入口为 null（那是事实，不要编一个） */
    fun currentRequestId(): String? = MDC.get(REQUEST_ID_KEY)

    /** 当前线程的登录者标识；未登录/非 HTTP 入口为 null */
    fun currentActor(): String? = MDC.get(UID_KEY)

    /** 记下本次请求的登录者，供请求完成日志读取（MDC 那份会在更内层被清掉，见 [ACTOR_ATTR]） */
    fun markActor(request: HttpServletRequest, actor: String) {
        request.setAttribute(ACTOR_ATTR, actor)
    }

    /** 本次请求的登录者：先看 attribute（跨得过 MDC 的清理），再退回 MDC */
    fun actorOf(request: HttpServletRequest): String? =
        request.getAttribute(ACTOR_ATTR) as? String ?: currentActor()

    /** 记下本次响应的业务码，供请求完成日志读取 */
    fun markBizCode(request: HttpServletRequest, code: Int) {
        request.setAttribute(BIZ_CODE_ATTR, code)
    }
}
