package top.tcyeee.bookmarkify.config.websocket

import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import top.tcyeee.bookmarkify.config.log
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
    ): Boolean {
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
            ?: return reject(response, "缺少 token 参数")
        val token = UriUtils.decode(rawToken, Charsets.UTF_8)
            .takeIf { it.isNotBlank() }
            ?: return reject(response, "token 解码后为空")
        // realm 缺省 USER（兼容 web 端旧连接）；仅显式 realm=ADMIN 时走管理端账号体系。
        val realm = if (queryParams.getFirst("realm")?.uppercase() == RoleEnum.ADMIN.name)
            RoleEnum.ADMIN.name else RoleEnum.USER.name
        val stp = if (realm == RoleEnum.ADMIN.name) StpKit.ADMIN else StpKit.USER
        val uid = stp.getLoginIdByToken(token)
            ?: return reject(response, "token 无对应会话(已过期或伪造), realm=$realm")
        attributes["uid"] = uid
        attributes["realm"] = realm
        return true
    }

    /**
     * 握手鉴权失败必须 `return false` + 401，**不能抛异常**。
     *
     * 从 beforeHandshake 抛出的异常会被 Spring 包成 HandshakeFailureException 一路冒到
     * DispatcherServlet，那里没人接，于是每一次 token 失效都变成一条 HTTP 500 加一整篇堆栈。
     * token 过期是再正常不过的客户端状态（换设备、退登、页面挂后台太久），把它记成服务端错误
     * 会让 500 曲线完全失去意义——线上就是这么被一个循环重连的坏 token 刷出「服务器持续 500」的。
     * 顺带一提前端拿到 500 和拿到 401 的行为也该不同：401 才是「别再拿这个 token 重试了」。
     *
     * 原因只记 debug 且不打印 token 本身：这是会话凭据，且失败量完全由客户端控制，
     * 记到 warn 等于把日志级别的控制权交给任何一个能发请求的人。
     */
    private fun reject(response: ServerHttpResponse, reason: String): Boolean {
        response.setStatusCode(HttpStatus.UNAUTHORIZED)
        log.debug("[WEBSOCKET] 握手鉴权失败，拒绝连接: {}", reason)
        return false
    }

    override fun afterHandshake(
        request: ServerHttpRequest, response: ServerHttpResponse, wsHandler: WebSocketHandler, exception: Exception?
    ) {
    }
}