package top.tcyeee.bookmarkify.utils

import top.tcyeee.bookmarkify.config.websocket.SessionManager
import top.tcyeee.bookmarkify.config.websocket.SocketMsgType
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO

/**
 * @author tcyeee
 * @date 3/15/25 20:33
 */
object SocketUtils {

    /**
     * 向前端通知当前桌面元素发生变动
     */
    fun homeItemUpdate(uid: String, nodeVO: UserLayoutNodeVO) = send(SocketMsgType.HOME_ITEM_UPDATE, uid, nodeVO)

    private fun send(type: SocketMsgType, uid: String, data: Any) = SessionManager.send(type, uid, data)
}