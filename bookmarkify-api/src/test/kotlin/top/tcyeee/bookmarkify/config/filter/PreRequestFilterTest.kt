package top.tcyeee.bookmarkify.config.filter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import top.tcyeee.bookmarkify.config.monitor.LogContext

/**
 * 请求完成日志的契约。
 *
 * 这一行是本次可观测性改动里最容易悄悄失效的东西：它打在 Filter 的 `finally` 里，而它要用的
 * 两个值分别由**更内层**的组件写入（业务码来自 `GlobalExceptionHandler`，登录者来自
 * `MdcUserInterceptor`）。其中 uid 已经踩过一次：拦截器的 `afterCompletion` 跑在
 * DispatcherServlet 内，比这个 `finally` **更早**执行，所以只从 MDC 取的话，线上每一行
 * 完成日志的 uid 都会是空的 —— 而这种失效没有任何报错，只是那一栏永远是 `-`。
 *
 * 所以这里用一个 ListAppender 把日志抓下来，直接断言那一行的内容与级别。
 */
@DisplayName("请求完成日志：级别按结果分档，uid/业务码必须真的取到")
class PreRequestFilterTest {

    private val filter = PreRequestFilter(ObjectMapper())
    private val logger = LoggerFactory.getLogger(PreRequestFilter::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private var originalLevel: Level? = null

    @BeforeEach
    fun attachAppender() {
        appender.start()
        logger.addAppender(appender)
        originalLevel = logger.level
        logger.level = Level.DEBUG
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = originalLevel
    }

    /** 完成日志那一行（排除限流告警等其它行） */
    private fun completionLine(): ILoggingEvent =
        appender.list.first { it.formattedMessage.startsWith("⛱") }

    private fun request(uri: String, method: String = "GET") =
        MockHttpServletRequest(method, uri).apply { addHeader("satoken", "test-token-$uri") }

    private fun run(
        request: MockHttpServletRequest,
        bizCode: Int? = null,
        actor: String? = null,
        sleepMillis: Long = 0,
    ): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        // 这个链条扮演"更内层的组件"：业务码由 GlobalExceptionHandler 写，登录者由
        // MdcUserInterceptor 写（并在 afterCompletion 里清掉 MDC —— 这里一并模拟）
        val chain = object : MockFilterChain() {
            override fun doFilter(req: jakarta.servlet.ServletRequest, res: jakarta.servlet.ServletResponse) {
                if (bizCode != null) LogContext.markBizCode(request, bizCode)
                if (actor != null) LogContext.markActor(request, actor)
                if (sleepMillis > 0) Thread.sleep(sleepMillis)
            }
        }
        filter.doFilter(request, response, chain)
        return response
    }

    @Test
    @DisplayName("成功请求打 info，并带上 uid 与业务码")
    fun `successful request logs info with actor`() {
        run(request("/bookmark/query", "POST"), bizCode = 0, actor = "uid-123")

        val line = completionLine()
        assertThat(line.level).isEqualTo(Level.INFO)
        assertThat(line.formattedMessage)
            .contains("POST /bookmark/query")
            .contains("-> 200/0")
            // 回归点：uid 必须真的取到。只读 MDC 时这里恒为 "uid=-"
            .contains("uid=uid-123")
    }

    @Test
    @DisplayName("业务失败打 warn —— 否则「哪个接口在报错」仍然没有数据源")
    fun `business failure logs warn`() {
        run(request("/bookmark/addOne", "POST"), bizCode = 304, actor = "uid-9")

        assertThat(completionLine().level).isEqualTo(Level.WARN)
    }

    @Test
    @DisplayName("未登录(101)不算失败：它是前端常态，算进去会把这个级别用废")
    fun `not logged in is not a failure`() {
        run(request("/user/me"), bizCode = 101)

        val line = completionLine()
        assertThat(line.level).isEqualTo(Level.INFO)
        assertThat(line.formattedMessage).contains("uid=-")
    }

    @Test
    @DisplayName("慢请求打 warn 并标 slow，正常请求不标")
    fun `slow request is flagged`() {
        // 真的睡过阈值(1500ms)而不是把常量暴露出去：这一档的意义全在"多慢算慢"，
        // 而断言一个自己注入的阈值等于什么都没验。代价是本测试套慢 1.6 秒
        run(request("/bookmark/slow", "POST"), bizCode = 0, sleepMillis = 1600)

        val slow = completionLine()
        assertThat(slow.level).isEqualTo(Level.WARN)
        assertThat(slow.formattedMessage).endsWith("slow")

        appender.list.clear()
        run(request("/bookmark/fast", "POST"), bizCode = 0)
        assertThat(completionLine().formattedMessage).doesNotContain("slow")
    }

    @Test
    @DisplayName("健康检查不打 info：按分钟轮询，一天 1440 行纯噪音")
    fun `health check is quiet`() {
        run(request("/internal/health"))

        val line = completionLine()
        assertThat(line.level).isEqualTo(Level.DEBUG)
    }

    @Test
    @DisplayName("限流告警里的 token 必须是打码的——它是一把 30 天有效的钥匙")
    fun `throttle warning masks the token`() {
        val token = "0199e4f1-7a2b-4c3d-8e5f-6a7b8c9d0e1f"
        // 窗口是 1 秒 20 次，第 21 次才会被限流
        repeat(21) {
            val request = MockHttpServletRequest("GET", "/bookmark/query").apply { addHeader("satoken", token) }
            filter.doFilter(request, MockHttpServletResponse(), MockFilterChain())
        }

        val throttled = appender.list.filter { it.formattedMessage.contains("请求过于频繁") }
        assertThat(throttled).isNotEmpty()
        assertThat(throttled.last().formattedMessage)
            .doesNotContain(token)
            .contains("0199…0e1f")
    }

    @Test
    @DisplayName("requestId 回写响应头，并在请求结束后从 MDC 清掉")
    fun `request id is returned and then cleared`() {
        val response = run(request("/user/me"), bizCode = 0)

        assertThat(response.getHeader(LogContext.REQUEST_ID_HEADER)).hasSize(8)
        // Tomcat 线程是复用的：不清理会把这次的 id 泄漏进下一个不相关的请求
        assertThat(LogContext.currentRequestId()).isNull()
    }
}
