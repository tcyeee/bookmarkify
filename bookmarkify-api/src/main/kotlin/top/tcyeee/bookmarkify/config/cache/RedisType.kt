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
    /* 等待被验证的短信验证码 */
    CODE_PHONE(3, TimeUnit.MINUTES),

    /* 等待被验证的邮箱验证码 */
    CODE_EMAIL(15, TimeUnit.MINUTES),

    /* 等待被验证的图形验证码 */
    CAPTCHA_CODE(3, TimeUnit.MINUTES),

    /* 默认背景图片 */
    DEFAULT_BACKGROUND_IMAGES(12, TimeUnit.HOURS),

    /* 默认背景渐变 */
    DEFAULT_BACKGROUND_GRADIENTS(12, TimeUnit.HOURS),

    /* 企业微信 ACCESS_TOKEN */
    WECHAT_WORK_ACCESS_TOKEN(110, TimeUnit.MINUTES)
}