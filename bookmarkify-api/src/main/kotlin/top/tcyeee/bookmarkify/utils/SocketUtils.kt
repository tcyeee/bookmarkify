package top.tcyeee.bookmarkify.utils

import top.tcyeee.bookmarkify.config.websocket.SessionManager
import top.tcyeee.bookmarkify.config.websocket.SocketMsgType
import top.tcyeee.bookmarkify.entity.ShareStatusChangedVO
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.dto.SimilarIngestUpdate
import top.tcyeee.bookmarkify.entity.entity.RoleEnum

/**
 * @author tcyeee
 * @date 3/15/25 20:33
 */
object SocketUtils {

    /**
     * 向前端通知当前桌面元素发生变动（USER realm）
     */
    fun homeItemUpdate(uid: String, nodeVO: UserLayoutNodeVO) =
        SessionManager.send(SocketMsgType.HOME_ITEM_UPDATE, RoleEnum.USER.name, uid, nodeVO)

    /**
     * 向发起「一键收录」的管理员推送某站点的收录进度（ADMIN realm）
     */
    fun similarIngestUpdate(adminUid: String, update: SimilarIngestUpdate) =
        SessionManager.send(SocketMsgType.SIMILAR_INGEST_UPDATE, RoleEnum.ADMIN.name, adminUid, update)

    /**
     * 向分享人推送分享状态变化（如异步 AI 审核未通过被下架，USER realm）
     */
    fun shareStatusChanged(uid: String, payload: ShareStatusChangedVO) =
        SessionManager.send(SocketMsgType.SHARE_STATUS_CHANGED, RoleEnum.USER.name, uid, payload)
}
