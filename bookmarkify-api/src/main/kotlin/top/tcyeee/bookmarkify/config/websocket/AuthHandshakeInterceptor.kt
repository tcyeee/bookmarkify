package top.tcyeee.bookmarkify.config.websocket

import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.util.UriComponentsBuilder
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.utils.StpKit

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
        // 用 UriComponentsBuilder 严格按 query param 名称取值，避免 substringAfter("token=")
        // 把 ?xtoken=foo 之类误识为 token。
        val token = UriComponentsBuilder.fromUri(request.uri).build().queryParams.getFirst("token")
            ?.takeIf { it.isNotBlank() }
            ?: throw CommonException(ErrorType.E201)
        val uid = StpKit.USER.getLoginIdByToken(token)
            ?: throw CommonException(ErrorType.E201)
        attributes["uid"] = uid
    }

    override fun afterHandshake(
        request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?
    ) {
    }
}