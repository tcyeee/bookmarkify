package top.tcyeee.bookmarkify.config.websocket

import cn.hutool.json.JSONUtil
import org.springframework.web.socket.TextMessage

/**
 * @author tcyeee
 * @date 3/15/25 21:26
 */
data class Message(
    val type: SocketMsgType,
    val data: Any,
) {
    fun msg(): TextMessage = TextMessage(JSONUtil.toJsonStr(this))
}
