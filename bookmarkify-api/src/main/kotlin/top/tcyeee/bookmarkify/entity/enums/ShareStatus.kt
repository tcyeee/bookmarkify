package top.tcyeee.bookmarkify.entity.enums

/**
 * 用户分享状态
 * @author tcyeee
 */
enum class ShareStatus {
    // 正常
    NORMAL,
    // 到期下架（不落库，由 expireTime 计算得出）
    EXPIRED,
    // 管理员强制下架
    ADMIN_TAKEDOWN
}
