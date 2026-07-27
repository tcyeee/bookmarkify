package top.tcyeee.bookmarkify.config.event

/** 分享已发布，触发异步 AI 内容审核；若发现违规内容会将分享状态回退为 REVIEW_REJECTED 并下架。 */
data class ShareAiReviewEvent(val shareId: String)
