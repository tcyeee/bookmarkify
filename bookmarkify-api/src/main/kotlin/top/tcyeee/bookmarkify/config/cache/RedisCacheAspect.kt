package top.tcyeee.bookmarkify.config.cache

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.utils.RedisUtils

@Aspect
@Component
class RedisCacheAspect {

    @Around("@annotation(param)")
    fun around(pjp: ProceedingJoinPoint, param: RedisCache): Any? =
        RedisUtils.get<Any>(param.type) ?: pjp.proceed().also { RedisUtils.set(param.type, it) }
}
