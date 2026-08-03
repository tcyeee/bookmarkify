package top.tcyeee.bookmarkify.entity.dto

/**
 * 一条「用户桌面上还在转圈」的书签，由对账任务补投递解析事件。
 *
 * [bookmarkId] 为字面量 `"LOADING"` 表示这是批量导入写下的占位、还没绑定到 canonical 书签，
 * 需要走「按网址解析并重新绑定」那条路；否则说明 canonical 书签已确定，只是解析没跑完。
 */
data class StuckLoadingItem(
    val userLinkId: String,
    val uid: String,
    val bookmarkId: String?,
    val urlFull: String,
    val layoutNodeId: String,
) {
    /** 导入占位符：尚未绑定 canonical 书签。 */
    val unbound: Boolean get() = bookmarkId.isNullOrBlank() || bookmarkId == UNBOUND_BOOKMARK_ID

    companion object {
        /** 批量导入时写进 bookmark_user_link.bookmark_id 的占位值，见 BookmarkUserLink 的导入构造函数。 */
        const val UNBOUND_BOOKMARK_ID = "LOADING"
    }
}

/**
 * 「此刻有多少用户桌面在转圈」的快照，由 `BookmarkUserLinkMapper.stuckLoadingStats` 产出。
 *
 * 这是添加书签这条链路唯一真正的 SLI —— 整套「同步段 + 占位 + WebSocket 推送 + 四个对账任务」
 * 的设计，成败就体现在这两个数字上。此前它们没有任何一处被观测：`scrapper_call_log` 记的是
 * 单次调用、`bookmark_ping_log` 记的是巡检，都回答不了「用户现在还在等的有几条、等了多久」。
 *
 * @param total 仍停在 `BOOKMARK_LOADING` 的节点总数
 * @param oldestAgeSeconds 其中最久的那条已经转了多少秒
 * @param importPending 其中属于批量导入积压（尚未绑定 canonical 书签）的条数
 */
data class StuckLoadingStats(
    // 刻意**不给默认值**，与 [StuckLoadingItem] 保持一致：全部参数都有默认值时 Kotlin 会额外
    // 生成一个无参构造，MyBatis 会优先挑它，然后试图用 setter 回填——而 `val` 没有 setter，
    // 于是三个字段静默地全是 0，日志里永远显示"没有人在转圈"。没有无参构造时 MyBatis 走
    // 构造参数名映射，这正是 StuckLoadingItem 一直在用、也确实生效的那条路径。
    val total: Long,
    val oldestAgeSeconds: Long,
    val importPending: Long,
)
