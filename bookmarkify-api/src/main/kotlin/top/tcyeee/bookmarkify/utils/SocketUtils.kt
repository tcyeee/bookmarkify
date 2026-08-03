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
     * 向前端推送**单个书签节点**的内容更新（USER realm）。
     *
     * 只接受 `type=BOOKMARK` 且 `typeApp` 非空的节点；文件夹用 [homeDirUpdate]，整树用
     * [homeLayoutRefresh]。三者形状不同，前端要按不同方式套用，混在一个类型里前端无从分辨。
     */
    fun homeItemUpdate(uid: String, nodeVO: UserLayoutNodeVO) =
        SessionManager.send(SocketMsgType.HOME_ITEM_UPDATE, RoleEnum.USER.name, uid, nodeVO)

    /** 向前端推送**单个文件夹**及其直接子节点，用于移动/建夹后的结构同步（USER realm）。 */
    fun homeDirUpdate(uid: String, dirVO: UserLayoutNodeVO) =
        SessionManager.send(SocketMsgType.HOME_DIR_UPDATE, RoleEnum.USER.name, uid, dirVO)

    /** 向前端推送**整棵桌面布局树**，用于结构变动过大时的整体重置（USER realm）。 */
    fun homeLayoutRefresh(uid: String, rootVO: UserLayoutNodeVO) =
        SessionManager.send(SocketMsgType.HOME_LAYOUT_REFRESH, RoleEnum.USER.name, uid, rootVO)

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
