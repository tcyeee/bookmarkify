package top.tcyeee.bookmarkify.config.websocket

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.web.socket.TextMessage

/**
 * @author tcyeee
 * @date 3/15/25 21:26
 */
data class Message(
    val type: SocketMsgType,
    val data: Any,
) {
    companion object {
        // 推送的 VO（如 BookmarkShow.logo）大量使用「无回填字段的 getter」承载嵌套结构；
        // Hutool JSONUtil 的 BeanDesc 只按字段反射，看不到这类 getter，会静默丢字段（曾导致
        // WebSocket 推送只同步了书签名称、漏掉了 logo）。改用与 REST 响应一致的 Jackson。
        private val mapper = jacksonObjectMapper().apply { registerModule(JavaTimeModule()) }
    }

    fun msg(): TextMessage = TextMessage(mapper.writeValueAsString(this))
}
