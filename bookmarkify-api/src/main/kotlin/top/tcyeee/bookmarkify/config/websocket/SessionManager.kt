package top.tcyeee.bookmarkify.config.websocket

import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.entity.RoleEnum
import java.util.concurrent.ConcurrentHashMap

/**
 * @author tcyeee
 * @date 3/15/25 21:06
 */
object SessionManager {
    // Spring 的 WebSocketSession 不是线程安全的；并发 send 会触发
    // IllegalStateException("TEXT_PARTIAL_WRITING")，所以包一层 decorator 序列化写入。
    private const val SEND_TIME_LIMIT_MS = 5_000
    private const val BUFFER_SIZE_LIMIT = 64 * 1024
    // key 为 "realm:uid"。USER 与 ADMIN 同属一个 sys_user id 空间，若仅用 uid 作 key，
    // 同一账号同时连 web(USER) 与管理端(ADMIN) 会互相挤掉，故按 realm 命名空间隔离。
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    fun add(session: WebSocketSession) {
        val key = key(session)
        val wrapped: WebSocketSession =
            ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT)
        // 同一 realm 下旧连接立即关闭，避免句柄泄漏与"消息发到孤立 session"
        sessions.put(key, wrapped)?.let { prior ->
            runCatching { prior.close() }
                .onFailure { log.warn("[WEBSOCKET] close prior session failed for key=$key: ${it.message}") }
        }
        log.info("[WEBSOCKET] new session key=$key total=${sessions.size}")
    }

    fun remove(session: WebSocketSession) {
        // 仅当当前注册的 session 就是被移除的这个时才删（避免新连接进来后误删）
        val key = key(session)
        sessions.compute(key) { _, current ->
            when {
                current == null -> null
                current.id == session.id -> null
                else -> current
            }
        }
    }

    fun send(type: SocketMsgType, realm: String, uid: String, content: Any) {
        sessions["$realm:$uid"]?.sendMessage(Message(type, content).msg())
        log.info("[WEBSOCKET] ${type.name} :Session has been sent to $realm:$uid]")
    }

    fun uid(session: WebSocketSession) = session.attributes.getValue("uid").toString()
    private fun realm(session: WebSocketSession) =
        session.attributes["realm"]?.toString() ?: RoleEnum.USER.name
    private fun key(session: WebSocketSession) = "${realm(session)}:${uid(session)}"
}
