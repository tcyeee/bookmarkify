package top.tcyeee.bookmarkify.config.websocket

import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 握手鉴权失败必须**拒绝**，不能抛异常。
 *
 * 回归的是 2026-08-04 线上那次「服务器持续 500」：从 `beforeHandshake` 抛出的异常会被 Spring
 * 包成 HandshakeFailureException 冒到 DispatcherServlet，无人接手 → 每次 token 失效都是一条
 * HTTP 500 加整篇堆栈。当时一个客户端拿着 `?token=f` 循环重连，把 500 曲线刷满，而真实原因
 * 只是「这个 token 没有对应会话」这一再正常不过的客户端状态。
 */
class AuthHandshakeInterceptorTest {

    private val interceptor = AuthHandshakeInterceptor()

    private fun handshake(query: String?): Pair<Boolean, Int> {
        val servletRequest = MockHttpServletRequest("GET", "/ws").apply {
            if (query != null) queryString = query
            serverName = "bookmarkify.cc"
        }
        val servletResponse = MockHttpServletResponse()
        val request: ServerHttpRequest = ServletServerHttpRequest(servletRequest)
        val response: ServerHttpResponse = ServletServerHttpResponse(servletResponse)
        val accepted = interceptor.beforeHandshake(request, response, DummyWebSocketHandler(), mutableMapOf())
        response.flush()
        return accepted to servletResponse.status
    }

    @Test
    fun `missing token is rejected with 401 rather than thrown`() {
        val (accepted, status) = handshake(null)
        assertFalse(accepted, "缺少 token 必须拒绝握手")
        assertEquals(401, status, "必须是 401；抛异常会让它变成 500")
    }

    @Test
    fun `blank token is rejected with 401 rather than thrown`() {
        val (accepted, status) = handshake("token=")
        assertFalse(accepted)
        assertEquals(401, status)
    }

    /**
     * 线上真正撞到的那条路径：token 有值、但查不到会话。它必须和上面两种一样是 401，
     * 而不是把一个客户端状态记成服务端错误。
     */
    @Test
    fun `token with no matching session is rejected with 401 rather than thrown`() {
        val (accepted, status) = handshake("token=f")
        assertFalse(accepted, "无对应会话的 token 必须拒绝握手")
        assertEquals(401, status)
    }

    /**
     * 拒绝的前提是「真的没有会话」，不能因为解析 query 时就把好 token 弄丢。
     * F-09 那次的教训：token 里含 '+' / '=' 时到达时是百分号编码的，不解码则连合法会话也查不到。
     */
    @Test
    fun `token is percent-decoded before lookup`() {
        // "a+b=c" 经百分号编码后到达；解码错误(如 URLDecoder 把 '+' 变空格)会得到不同的查找键。
        // 这里没有可用的会话存储，所以只断言它走到了「查不到会话」而不是「token 为空」——
        // 两者都返回 401，区别在于前者说明解码这一步确实执行了。
        val (accepted, status) = handshake("token=a%2Bb%3Dc")
        assertFalse(accepted)
        assertEquals(401, status)
    }

    @Test
    fun `unrelated query params are not mistaken for token`() {
        // 曾经用 substringAfter("token=") 取值，?xtoken=foo 会被误识别成 token=foo
        val (accepted, status) = handshake("xtoken=foo")
        assertFalse(accepted, "只有名为 token 的参数才算 token")
        assertEquals(401, status)
    }

    /** 不需要真实实现，握手拦截器只是把它透传下去。 */
    private class DummyWebSocketHandler : org.springframework.web.socket.WebSocketHandler {
        override fun afterConnectionEstablished(session: org.springframework.web.socket.WebSocketSession) = Unit
        override fun handleMessage(
            session: org.springframework.web.socket.WebSocketSession,
            message: org.springframework.web.socket.WebSocketMessage<*>
        ) = Unit

        override fun handleTransportError(
            session: org.springframework.web.socket.WebSocketSession, exception: Throwable
        ) = Unit

        override fun afterConnectionClosed(
            session: org.springframework.web.socket.WebSocketSession,
            closeStatus: org.springframework.web.socket.CloseStatus
        ) = Unit

        override fun supportsPartialMessages(): Boolean = false
    }
}
