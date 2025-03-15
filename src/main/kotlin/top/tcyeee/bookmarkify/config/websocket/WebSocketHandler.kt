package top.tcyeee.bookmarkify.config.websocket

import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import top.tcyeee.bookmarkify.utils.BaseUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * @author tcyeee
 * @date 3/15/25 14:51
 */
@Component
class WebSocketHandler : TextWebSocketHandler() {
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val token = session.attributes.getValue("token").toString()
        val uid = BaseUtils.getUidByToken(token)
        sessions[uid] = session
        println("New WebSocket connection:$uid current users count: ${sessions.size}")
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val receivedMessage = message.payload
        println("Received message: $receivedMessage")
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
//        sessions.remove()
    }
}