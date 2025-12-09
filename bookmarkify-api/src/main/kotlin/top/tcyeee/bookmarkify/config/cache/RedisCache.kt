package top.tcyeee.bookmarkify.config.cache

import top.tcyeee.bookmarkify.config.cache.RedisType

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RedisCache(val type: RedisType)

