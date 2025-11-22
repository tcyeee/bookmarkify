package top.tcyeee.bookmarkify.utils

import jakarta.annotation.PostConstruct
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.entity.redis.RedisType

/**
 * REDIS 工具类
 *
 * @author tcyeee
 * @date 4/11/25 14:07
 */
@Component
class RedisUtils(private val redisTemplate: RedisTemplate<String, Any>) {
    companion object {
        lateinit var template: ValueOperations<String, Any>

        fun set(type: RedisType, value: Any) = set(type, BaseUtils.uid(), value)
        fun set(type: RedisType, uid: String, value: Any) = template.set(key(type, uid), value, type.expire, type.unit)

        inline fun <reified T> get(type: RedisType): T? = get(type, BaseUtils.uid())
        inline fun <reified T> get(type: RedisType, uid: String): T? = template.get(key(type, uid)) as? T

        fun key(type: RedisType, uid: String) = "${type.name}:$uid"
    }

    @PostConstruct
    fun init() {
        template = redisTemplate.opsForValue()
    }
}