package top.tcyeee.bookmarkify.config.init

import cn.hutool.core.util.IdUtil
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.time.Duration

/**
 * 「本服务当前只能单实例运行」这条约束的护栏。
 *
 * ## 为什么需要它
 *
 * 有两处硬依赖单实例，且**都不会在多实例下报错，只会静默算错**：
 *
 * 1. [top.tcyeee.bookmarkify.config.websocket.SessionManager] 把 WebSocket 连接存在进程内的
 *    `ConcurrentHashMap` 里。负载均衡后面挂两个实例时，一次 `HOME_ITEM_UPDATE` 只能推给连在
 *    **本实例**上的那些连接，连在另一台上的用户什么都收不到 —— 表现是"书签一直转圈"，而日志
 *    里那条 `no online session` 看起来完全正常。
 * 2. `@Scheduled` 没有分布式锁。四个对账任务会在每个实例上各跑一遍：重复 ping（直接把 scrapper
 *    的并发上限吃满，503 被判成 UNKNOWN，再把巡检自己熔断掉）、重复投递解析事件。
 *    [top.tcyeee.bookmarkify.config.async.ParseLock] 在 Redis 里，能挡住一部分抓取层面的重复，
 *    但挡不住重复的 ping 与重复的调度状态推进 —— 它反而会让症状更难排查。
 *
 * 也就是说：**加一个实例不会失败，只会让一半的推送消失**。这个类的全部作用就是让那件事
 * 失败得响亮 —— 它不阻止启动（生产环境里因为一把锁起不来更糟），只保证控制台上有一条
 * 无法忽略的 error，并且每分钟重复一次。
 *
 * ## 何时可以删掉它
 *
 * 上了 ShedLock（或等价的分布式调度锁）**并且**给 WebSocket 推送加了 Redis pub/sub 扇出之后。
 * 两者缺一不可，只做其中一个仍然是坏的。
 */
@Component
class SingleInstanceGuard(private val redis: StringRedisTemplate) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 本实例的身份。带上主机名与 PID，冲突时那条 error 能直接告诉运维另一台是谁。 */
    private val instanceId: String = runCatching {
        "${InetAddress.getLocalHost().hostName}/${ManagementFactory.getRuntimeMXBean().name}"
    }.getOrElse { "unknown/${IdUtil.fastSimpleUUID()}" }

    /** 关掉护栏。仅用于「明知在多实例下跑、且已经接受后果」的场景（比如临时的蓝绿切换窗口）。 */
    @Value("\${bookmarkify.single-instance-guard.enabled:true}")
    private var enabled: Boolean = true

    /**
     * 续约兼冲突检测。
     *
     * 周期必须**明显短于** [OWNERSHIP_TTL]，否则自己的租约会在两次续约之间过期，
     * 于是本实例把自己误报成"第二个实例"。
     */
    @Scheduled(fixedDelay = RENEW_INTERVAL_MS, initialDelay = 0)
    fun renewOwnership() {
        if (!enabled) return
        runCatching {
            val ops = redis.opsForValue()
            // 抢占：没人持有就写上自己
            if (ops.setIfAbsent(OWNER_KEY, instanceId, OWNERSHIP_TTL) == true) {
                log.info("[SingleInstanceGuard] 已取得单实例所有权: instance=$instanceId")
                return@runCatching
            }
            val owner = ops.get(OWNER_KEY)
            when {
                // 正常路径：自己续期
                owner == instanceId -> redis.expire(OWNER_KEY, OWNERSHIP_TTL)
                // 上一任刚过期又被别人抢走、或者真的起了第二个实例。两者在这里无法区分，
                // 但都值得报出来 —— 前者说明实例在反复重启，同样不正常
                owner != null -> log.error(
                    "[SingleInstanceGuard] 检测到另一个实例正在运行: 本机=$instanceId, 持有者=$owner。" +
                        "本服务当前**不支持多实例**：WebSocket 会话存在进程内存里(推送只能到达连在其中一台上的用户)，" +
                        "@Scheduled 也没有分布式锁(四个对账任务会各跑一遍，重复 ping 会把 scrapper 并发打满并触发巡检熔断)。" +
                        "请立即下线其中一个实例；确需横向扩容必须先接入 ShedLock + WebSocket 推送的 Redis pub/sub 扇出。"
                )
                // get 回来是 null：键在 setIfAbsent 与 get 之间过期了，下一轮自然会重新抢占
                else -> log.debug("[SingleInstanceGuard] 所有权键刚好过期，下一轮重试")
            }
        }.onFailure {
            // Redis 不可用不该让应用起不来或刷屏，与 ParseLock / ThrottleAspect 的降级策略一致
            log.warn("[SingleInstanceGuard] 所有权检查失败(忽略): ${it.message}")
        }
    }

    /** 优雅停机时主动让出，免得下一次部署要干等一个 TTL 才不报冲突。 */
    @PreDestroy
    fun releaseOwnership() {
        if (!enabled) return
        runCatching {
            if (redis.opsForValue().get(OWNER_KEY) == instanceId) redis.delete(OWNER_KEY)
        }.onFailure { log.warn("[SingleInstanceGuard] 释放所有权失败(忽略): ${it.message}") }
    }

    companion object {
        private const val OWNER_KEY = "bookmarkify:instance:owner"
        private const val RENEW_INTERVAL_MS = 60_000L
        private val OWNERSHIP_TTL: Duration = Duration.ofMinutes(3)
    }
}
