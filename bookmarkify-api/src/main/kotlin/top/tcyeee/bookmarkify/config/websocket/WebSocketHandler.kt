package top.tcyeee.bookmarkify.config.websocket

import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import top.tcyeee.bookmarkify.config.log

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

    /**
     * 目前入站消息只有心跳。
     *
     * **必须回 `pong`**：客户端每 5s 发一次 `ping`，但 WebSocket 的 `send` 在半开连接
     * （移动网络切换、NAT 表项超时、笔记本合盖）上不会立刻报错，`readyState` 也照旧是 OPEN。
     * 只发不收的心跳因此什么都证明不了——连接早就废了，客户端却既不重连也收不到推送，
     * 表现就是"加了书签一直转圈"。有了回帧，客户端才能靠"多久没收到任何帧"判定链路已死。
     *
     * 回帧走 [SessionManager.wrapperOf] 取回的并发安全包装：这里是容器的入站线程，
     * 而推送在业务线程上，直接往原始 session 写会撞出 TEXT_PARTIAL_WRITING。
     */
    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        if (message.payload != "ping") return
        val target = SessionManager.wrapperOf(session) ?: return
        runCatching { target.sendMessage(TextMessage("pong")) }
            .onFailure { log.warn("[WEBSOCKET] pong failed for session=${session.id}: ${it.message}") }
    }
}