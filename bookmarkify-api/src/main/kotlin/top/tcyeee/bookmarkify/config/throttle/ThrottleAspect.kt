package top.tcyeee.bookmarkify.config.throttle

import java.util.concurrent.TimeUnit
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.utils.BaseUtils

/**
 * 节流切面
 *
 * @author tcyeee
 */
@Aspect
@Component
class ThrottleAspect(private val stringRedisTemplate: StringRedisTemplate) {

    @Around("@annotation(throttle)")
    fun around(joinPoint: ProceedingJoinPoint, throttle: Throttle): Any? {
        val signature = joinPoint.signature as MethodSignature
        val method = signature.method

        // 方法唯一标识：类名.方法名
        val methodKey = "${method.declaringClass.name}.${method.name}"
        val uid = runCatching { BaseUtils.uid() }.getOrElse { throw CommonException(ErrorType.E101) }
        // Redis Key: throttle:UserID:方法唯一标识
        val key = "throttle:$uid:$methodKey"
        // 尝试设置 Key，如果不存在则设置成功返回 true
        // setIfAbsent 对应 SETNX
        val success = stringRedisTemplate.opsForValue()
            .setIfAbsent(key, "1", throttle.interval.toLong(), TimeUnit.MILLISECONDS)

        if (success == true) {
            return joinPoint.proceed()
        } else {
            val expire = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)
            val msg = if (expire > 0) "请求过于频繁，请等待 $expire 秒后再试" else "请求过于频繁，请稍后再试"
            throw CommonException(ErrorType.E107, msg)
        }
    }
}
