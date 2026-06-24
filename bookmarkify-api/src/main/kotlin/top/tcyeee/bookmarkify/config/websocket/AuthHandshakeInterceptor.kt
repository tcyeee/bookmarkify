package top.tcyeee.bookmarkify.config.websocket

import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.entity.RoleEnum
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
        // fromUri() stores the raw (percent-encoded) query string internally, so
        // getFirst() returns the encoded value (e.g. "abc%2Bdef"). We must decode it
        // with UriUtils.decode (RFC 3986) rather than URLDecoder (which wrongly maps '+' → space).
        // F-09: without this decode step, tokens containing '+' or '=' arrive percent-encoded
        // and Sa-Token's lookup fails even for a valid session.
        val queryParams = UriComponentsBuilder.fromUri(request.uri).build().queryParams
        val rawToken = queryParams.getFirst("token")
            ?.takeIf { it.isNotBlank() }
            ?: throw CommonException(ErrorType.E201)
        val token = UriUtils.decode(rawToken, Charsets.UTF_8)
            .takeIf { it.isNotBlank() }
            ?: throw CommonException(ErrorType.E201)
        // realm 缺省 USER（兼容 web 端旧连接）；仅显式 realm=ADMIN 时走管理端账号体系。
        val realm = if (queryParams.getFirst("realm")?.uppercase() == RoleEnum.ADMIN.name)
            RoleEnum.ADMIN.name else RoleEnum.USER.name
        val stp = if (realm == RoleEnum.ADMIN.name) StpKit.ADMIN else StpKit.USER
        val uid = stp.getLoginIdByToken(token)
            ?: throw CommonException(ErrorType.E201)
        attributes["uid"] = uid
        attributes["realm"] = realm
    }

    override fun afterHandshake(
        request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?
    ) {
    }
}