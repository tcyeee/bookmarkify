package top.tcyeee.bookmarkify.config.throttle

import java.util.concurrent.TimeUnit
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log
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
        // byIp=true（登录等未登录可暴力的接口）强制按客户端 IP 限流，防止轮换匿名 session 绕过；
        // 否则优先按 uid 限流，未登录场景回退到客户端 IP。
        val subject = when {
            throttle.byIp -> "ip:${clientIp()}"
            else -> runCatching { BaseUtils.uid() }.getOrElse { "ip:${clientIp()}" }
        }
        // Redis Key: throttle:Subject:方法唯一标识
        val key = "throttle:$subject:$methodKey"
        // 尝试设置 Key，如果不存在则设置成功返回 true
        // setIfAbsent 对应 SETNX
        val success = stringRedisTemplate.opsForValue()
            .setIfAbsent(key, "1", throttle.interval.toLong(), TimeUnit.MILLISECONDS)

        // F-04: setIfAbsent returns Boolean? — null means Redis connection failure.
        // Treat null as a degraded pass-through to avoid blocking legitimate users during outages.
        when (success) {
            true -> return joinPoint.proceed()
            false -> {
                val expire = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS)
                val msg = if (expire > 0) "请求过于频繁，请等待 $expire 秒后再试" else "请求过于频繁，请稍后再试"
                throw CommonException(ErrorType.E107, msg)
            }
            else -> {
                log.warn("[Throttle] Redis unavailable for key={}, skipping throttle check", key)
                return joinPoint.proceed()
            }
        }
    }

    private fun clientIp(): String {
        val attrs = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return "unknown"
        // F-02: X-Forwarded-For is NOT trusted here — it is trivially spoofed by clients,
        // which would allow bypassing IP-based rate limits by rotating header values.
        // Use the network-level remoteAddr (the actual TCP peer address) instead.
        return attrs.request.remoteAddr ?: "unknown"
    }
}
