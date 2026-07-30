package top.tcyeee.bookmarkify.config.async

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.log
import java.time.Duration

/**
 * 书签解析链路的互斥锁（Redis SETNX + TTL）。
 *
 * 解决两类重复劳动：
 * 1. **同一书签被并发抓取**——多个用户同时添加同一个 URL 时，canonical 记录靠唯一键收敛成一条，
 *    但每个用户各发一个解析事件，于是同一个页面被 ping/抓取多次，且多个 [SiteAssetWriter]
 *    [top.tcyeee.bookmarkify.server.asset.SiteAssetWriter] 的「先删后插」事务交错，
 *    资产行可能翻倍或丢失，OSS 孤儿回收的引用计数也会误删对方刚传上去的对象。
 * 2. **兜底任务与在途任务抢跑**——补投递的对账任务无从知道某条记录是否正在被处理。
 *
 * 两种用法的释放策略刻意不同：
 * - [bookmark] 抓取锁在任务结束时**主动释放**——工作已经做完，下一次正当的重新解析不该被残留的锁挡住。
 * - [dispatch] 补投递锁**只靠 TTL 过期**——成功时目标记录本身已不再满足重投条件，失败时留着锁
 *   恰好起到退避作用，免得一个必然失败的站点被每一轮对账反复重试。
 *
 * 两者都不是严格互斥（TTL 到期而任务仍在跑时会有重叠），也不需要是：代价上限是多抓一次，
 * 而不是数据损坏。真正要防的是「几十个用户同时添加同一个新站点」这种放大效应。
 *
 * Redis 不可用时**放行**（与 [top.tcyeee.bookmarkify.config.throttle.ThrottleAspect] 一致）：
 * 缓存故障不该让加书签这件事整个停摆，重复抓取只是浪费，比功能不可用轻。
 */
@Component
class ParseLock(private val redis: StringRedisTemplate) {

    /** 拿到锁返回 true。[ttl] 应当大于任务本身的最长耗时，否则锁会在任务还在跑时提前失效。 */
    fun tryAcquire(key: String, ttl: Duration): Boolean {
        val acquired = runCatching { redis.opsForValue().setIfAbsent("$KEY_PREFIX$key", "1", ttl) }
            .getOrElse {
                log.warn("[ParseLock] Redis 异常，降级放行: key={}, err={}", key, it.message)
                return true
            }
        return when (acquired) {
            true -> true
            false -> false
            // setIfAbsent 返回 null 表示连接异常
            else -> {
                log.warn("[ParseLock] Redis 不可用，降级放行: key={}", key)
                true
            }
        }
    }

    /** 提前释放。仅用于「确认无需再执行」的场景；正常路径依赖 TTL 自然过期即可。 */
    fun release(key: String) {
        runCatching { redis.delete("$KEY_PREFIX$key") }
            .onFailure { log.warn("[ParseLock] 释放锁失败(忽略): key={}, err={}", key, it.message) }
    }

    companion object {
        private const val KEY_PREFIX = "parse:lock:"

        /** 抓取一个书签的锁 key。 */
        fun bookmark(bookmarkId: String) = "bookmark:$bookmarkId"

        /** 补投递一条用户书签的解析任务的锁 key。 */
        fun dispatch(userLinkId: String) = "dispatch:$userLinkId"
    }
}
