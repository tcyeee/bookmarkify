package top.tcyeee.bookmarkify.utils

import top.tcyeee.bookmarkify.config.websocket.SessionManager
import top.tcyeee.bookmarkify.config.websocket.SocketMsgType
import top.tcyeee.bookmarkify.entity.HomeItemShow

/**
 * @author tcyeee
 * @date 3/15/25 20:33
 */
object SocketUtils {
    fun homeItemUpdate(uid: String, homeItem: HomeItemShow) = send(SocketMsgType.HOME_ITEM_UPDATE, uid, homeItem)
    private fun send(type: SocketMsgType, uid: String, data: Any) = SessionManager.send(type, uid, data)
}