package top.tcyeee.bookmarkify.config.websocket

import cn.dev33.satoken.stp.StpUtil
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType

/**
 * WebSocket 握手之前解析TOKEN
 *
 * @author tcyeee
 * @date 3/15/25 16:48
 */
class AuthHandshakeInterceptor : HandshakeInterceptor {
    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>
    ): Boolean = true.also {
        request.uri.query
            ?.substringAfter("token=")?.takeWhile { it != '&' }
            ?.let { StpUtil.getLoginIdByToken(it) }
            ?.apply { attributes["uid"] = this }
            ?: throw CommonException(ErrorType.E201)
    }

    override fun afterHandshake(
        request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?
    ) {
    }
}