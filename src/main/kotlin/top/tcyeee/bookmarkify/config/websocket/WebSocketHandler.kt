package top.tcyeee.bookmarkify.config.websocket

import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler

/**
 * @author tcyeee
 * @date 3/15/25 14:51
 */
@Component
class WebSocketHandler : TextWebSocketHandler() {
    override fun afterConnectionEstablished(session: WebSocketSession) {
        SessionManager.add(session)
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        SessionManager.remove(session)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
    }
}