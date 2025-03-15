package top.tcyeee.bookmarkify.config.websocket

import cn.hutool.core.util.StrUtil
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType

/**
 * @author tcyeee
 * @date 3/15/25 16:48
 */
class AuthHandshakeInterceptor : HandshakeInterceptor {
    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>
    ): Boolean {
        // 通过 URI 获取查询参数中的 token
        val queryParams = request.uri.query
        val token = queryParams?.substringAfter("token=")?.takeWhile { it != '&' }
        if (token == null) throw CommonException(ErrorType.E201)
        attributes["token"] = token
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?
    ) {
    }
}