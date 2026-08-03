package top.tcyeee.bookmarkify.config.cache

import java.util.concurrent.TimeUnit

/**
 * REDIS KEY 类型
 *
 * @author tcyeee
 * @date 4/11/25 14:11
 */
enum class RedisType(
    val expire: Long,   // 过期时间 -1:永不过期
    val unit: TimeUnit  // 过期时间单位
) {
    /* 等待被验证的邮箱验证码 */
    CODE_EMAIL(15, TimeUnit.MINUTES),

    /*
     * 注意：这里曾有一个 DEFAULT_BACKGROUND_IMAGES(12h)，但从未被任何 @RedisCache 使用。
     * 已移除而非留着 —— 背景图接口返回的是**限时签名 URL**，一旦有人顺手把 12h 缓存加上去，
     * 缓存里的签名会在 1h 后失效，表现为"12 小时里有 11 小时图是坏的"。
     * 若将来确实需要缓存这类接口，请缓存 object key 而不是签好名的 URL。
     */

    /* 默认背景渐变（纯色值，不含签名 URL，可安全缓存） */
    DEFAULT_BACKGROUND_GRADIENTS(12, TimeUnit.HOURS),

    /* 企业微信 ACCESS_TOKEN */
    WECHAT_WORK_ACCESS_TOKEN(1, TimeUnit.HOURS),

    /* 管理后台「重新获取」书签的暂存抓取结果（预览与应用之间桥接，按 pageId 区分） */
    BOOKMARK_REFETCH(10, TimeUnit.MINUTES)
}