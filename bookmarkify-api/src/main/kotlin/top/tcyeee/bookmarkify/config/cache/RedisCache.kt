package top.tcyeee.bookmarkify.config.cache

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RedisCache(val type: RedisType)

