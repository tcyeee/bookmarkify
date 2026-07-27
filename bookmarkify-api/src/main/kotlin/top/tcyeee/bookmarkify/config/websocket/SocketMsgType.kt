package top.tcyeee.bookmarkify.config.websocket

/**
 * @author tcyeee
 * @date 3/15/25 20:56
 */
enum class SocketMsgType {
    HOME_ITEM_UPDATE,

    /** 管理端「一键收录」相似网站的逐站进度（推送给 ADMIN realm 的会话） */
    SIMILAR_INGEST_UPDATE,

    /** 分享状态发生变化（如异步 AI 审核未通过被下架），推送给分享人（USER realm） */
    SHARE_STATUS_CHANGED,
}
