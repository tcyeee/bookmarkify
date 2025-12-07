package top.tcyeee.bookmarkify.utils

import top.tcyeee.bookmarkify.config.websocket.SessionManager
import top.tcyeee.bookmarkify.config.websocket.SocketMsgType
import top.tcyeee.bookmarkify.entity.BookmarkShow

/**
 * @author tcyeee
 * @date 3/15/25 20:33
 */
object SocketUtils {
    fun updateMarkbook(uid: String, bookmark: BookmarkShow) {
        SessionManager.send(SocketMsgType.BOOKMARK_UPDATE_ONE, uid, bookmark)
    }
}