package top.tcyeee.bookmarkify.config.websocket

import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import top.tcyeee.bookmarkify.config.log
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
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    fun add(session: WebSocketSession) {
        val uid = uid(session)
        val wrapped: WebSocketSession =
            ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT)
        // 同一用户旧连接立即关闭，避免句柄泄漏与"消息发到孤立 session"
        sessions.put(uid, wrapped)?.let { prior ->
            runCatching { prior.close() }
                .onFailure { log.warn("[WEBSOCKET] close prior session failed for uid=$uid: ${it.message}") }
        }
        log.info("[WEBSOCKET] new session uid=$uid total=${sessions.size}")
    }

    fun remove(session: WebSocketSession) {
        // 仅当当前注册的 session 就是被移除的这个时才删（避免新连接进来后误删）
        val uid = uid(session)
        sessions.compute(uid) { _, current ->
            when {
                current == null -> null
                current.id == session.id -> null
                else -> current
            }
        }
    }

    fun send(type: SocketMsgType, uid: String, content: Any) {
        session(uid)?.sendMessage(Message(type, content).msg())
        log.info("[WEBSOCKET] ${type.name} :Session has been sent to $uid]")
    }

    fun uid(session: WebSocketSession) = session.attributes.getValue("uid").toString()
    private fun session(uid: String) = sessions[uid]
}