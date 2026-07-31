package top.tcyeee.bookmarkify.entity.enums

/**
 * 一次第三方 AI 调用所服务的业务场景。
 *
 * 日志按场景分类而不是按 prompt 分类：prompt 会随着调优反复改写，场景不会。
 * 排查「分类突然全是 other」这类问题时，能筛出的必须是同一件业务的历史调用。
 */
enum class AiCallScene {
    /** 从网站标题中提取品牌简称 */
    APP_NAME,

    /** 从固定候选词表中为网站挑选分类（闭词表，自动抓取链路用） */
    CATEGORY_INFER,

    /** 为网站提议分类，允许提出词表里没有的新词（开词表，管理员手动触发） */
    CATEGORY_PROPOSE,

    /** 推荐功能/定位相似的其它网站 */
    SIMILAR_SITE,

    /** 判断网站是否涉及成人/赌博等不宜内容（fail-open） */
    NSFW_CHECK,

    /** 分享发布前的内容合规审核（fail-closed） */
    CONTENT_REVIEW,
}
