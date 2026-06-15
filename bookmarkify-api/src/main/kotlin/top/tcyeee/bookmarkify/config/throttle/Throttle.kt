package top.tcyeee.bookmarkify.config.throttle

/**
 * 节流注解
 * 用于限制接口调用频率
 *
 * @property interval 间隔时间，单位 ms
 * @property byIp 是否强制按客户端 IP 限流。未登录可暴力尝试的接口（登录等）必须置 true：
 *               否则攻击者可通过轮换匿名 session/uid 绕过按 uid 的限流。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Throttle(
    val interval: Int = 1000,
    val byIp: Boolean = false
)