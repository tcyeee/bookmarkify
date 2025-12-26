package top.tcyeee.bookmarkify.config.throttle

/**
 * 节流注解
 * 用于限制接口调用频率
 *
 * @property interval 间隔时间，单位 ms
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Throttle(
    val interval: Int = 1000
)