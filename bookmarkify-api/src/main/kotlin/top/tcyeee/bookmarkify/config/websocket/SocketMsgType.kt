package top.tcyeee.bookmarkify.config.websocket

/**
 * @author tcyeee
 * @date 3/15/25 20:56
 */
enum class SocketMsgType {
    /**
     * 单个书签节点的内容更新（抓取完成、LOADING 收口）。payload 是一个 `type=BOOKMARK`
     * 且 `typeApp` 非空的 [top.tcyeee.bookmarkify.entity.UserLayoutNodeVO]。
     *
     * **不要拿它推送文件夹或整棵树** —— 前端 `bookmark.store.ts:replaceContent` 只认这一种形状，
     * 其余一律丢弃。历史上文件夹更新和整树刷新都挤在这个类型里，结果是前端全部静默忽略、
     * 多标签页之间的结构同步从来没生效过。下面两个类型就是为此拆出来的。
     */
    HOME_ITEM_UPDATE,

    /** 单个文件夹节点及其直接子节点（`type=BOOKMARK_DIR` + `children`），用于移动/建夹后同步结构。 */
    HOME_DIR_UPDATE,

    /** 整棵桌面布局树的根节点，用于结构变动过大（文件夹被解散/删除）时让前端整体重置。 */
    HOME_LAYOUT_REFRESH,

    /** 管理端「一键收录」相似网站的逐站进度（推送给 ADMIN realm 的会话） */
    SIMILAR_INGEST_UPDATE,

    /** 分享状态发生变化（如异步 AI 审核未通过被下架），推送给分享人（USER realm） */
    SHARE_STATUS_CHANGED,

    /**
     * 系统书签集发布流程 · 步骤2 的逐条抓取进度（推送给 ADMIN realm 的会话）。
     * 与 [SIMILAR_INGEST_UPDATE] 分开是同一条理由：形状不同的进度混进一个类型，
     * 前端只能靠猜字段区分，稍不注意就会把一个流程的进度错当成另一个的。
     */
    SYSTEM_COLLECTION_CRAWL_UPDATE,
}
