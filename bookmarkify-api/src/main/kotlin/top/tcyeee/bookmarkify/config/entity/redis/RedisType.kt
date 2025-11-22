package top.tcyeee.bookmarkify.config.entity.redis

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
    CODE_PHONE(3, TimeUnit.MINUTES)     // 短信验证码
}