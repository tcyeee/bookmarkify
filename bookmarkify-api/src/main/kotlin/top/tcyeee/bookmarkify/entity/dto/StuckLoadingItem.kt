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
