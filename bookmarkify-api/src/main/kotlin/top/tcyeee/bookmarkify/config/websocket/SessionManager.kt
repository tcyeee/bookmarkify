package top.tcyeee.bookmarkify.config.websocket

import org.springframework.web.socket.WebSocketSession
import top.tcyeee.bookmarkify.config.log
import java.util.concurrent.ConcurrentHashMap

/**
 * @author tcyeee
 * @date 3/15/25 21:06
 */
object SessionManager {
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    fun add(session: WebSocketSession) {
        sessions[uid(session)] = session
        println("New WebSocket Count: ${sessions.size}")
    }

    fun remove(session: WebSocketSession) {
        sessions.remove(uid(session))
    }

    fun send(type: SocketMsgType, uid: String, content: Any) {
        session(uid)?.sendMessage(Message(type, content).msg())
        log.info("[WEBSOCKET] ${type.name} :Session has been sent to $uid]")
    }

    fun uid(session: WebSocketSession) = session.attributes.getValue("uid").toString()
    private fun session(uid: String) = sessions[uid]
}