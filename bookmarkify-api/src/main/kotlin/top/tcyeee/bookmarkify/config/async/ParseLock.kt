package top.tcyeee.bookmarkify.config.async

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.log
import java.time.Duration
import java.util.UUID

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

    /**
     * 拿到锁返回 true。[ttl] 应当大于任务本身的最长耗时，否则锁会在任务还在跑时提前失效。
     *
     * 只适用于**不主动释放**的场景（[dispatch]）。需要在 `finally` 里释放的，用
     * [acquire] + [release]，否则超时后会误删别人的锁。
     */
    fun tryAcquire(key: String, ttl: Duration): Boolean = acquire(key, ttl) != null

    /**
     * 拿锁并返回本次持有的**凭据**，拿不到返回 null。释放时把凭据传回 [release]。
     *
     * ## 为什么需要凭据
     *
     * 原来的实现写死一个 `"1"`，释放就是无条件 `DEL`。TTL 一旦短于任务的实际耗时，
     * 就会出现这个序列：A 超时 → 锁自然过期 → B 拿到锁开始跑 → A 终于跑完，`finally`
     * 里把 **B 的锁**删掉 → C 也进来了。锁不仅失效，还会**互相破坏**，而且越是"任务
     * 比预期慢"的时候（正是最需要互斥的时候）越容易发生。带上凭据做 CAS 删除之后，
     * 最坏情况退回到"锁失效"，不会再演变成"锁互删"。
     */
    fun acquire(key: String, ttl: Duration): String? {
        val token = UUID.randomUUID().toString()
        val acquired = runCatching { redis.opsForValue().setIfAbsent("$KEY_PREFIX$key", token, ttl) }
            .getOrElse {
                log.warn("[ParseLock] Redis 异常，降级放行: key={}, err={}", key, it.message)
                return token
            }
        return when (acquired) {
            true -> token
            false -> null
            // setIfAbsent 返回 null 表示连接异常
            else -> {
                log.warn("[ParseLock] Redis 不可用，降级放行: key={}", key)
                token
            }
        }
    }

    /**
     * 这把锁当前是否被人持有。**只用于展示**，不能用来做互斥判断。
     *
     * 与 [acquire] 之间天然有竞态：读到 false 之后、真正开跑之前，另一个实例完全可能把锁抢走。
     * 互斥仍然只由 `acquire` 的 SETNX 保证——这里回答的是"现在给管理员看的那句『上一轮还在跑』
     * 该不该显示"，读错的代价只是一句提示不准。
     *
     * Redis 异常时返回 false，与 [acquire] 的降级放行方向一致：缓存故障不该让界面显示成"正在跑"
     * 而把一个本来能点的按钮永久禁掉。
     */
    fun isHeld(key: String): Boolean =
        runCatching { redis.hasKey("$KEY_PREFIX$key") }.getOrDefault(false)

    /**
     * 提前释放，仅当锁仍然是 [token] 那一次持有时才删。
     *
     * 判断与删除必须在一次 Redis 往返里完成——分成 GET 再 DEL 的话，两步之间锁照样可能
     * 过期并被别人拿走，那就还是删了别人的锁，只是窗口小一点。
     */
    fun release(key: String, token: String) {
        runCatching { redis.execute(RELEASE_SCRIPT, listOf("$KEY_PREFIX$key"), token) }
            .onFailure { log.warn("[ParseLock] 释放锁失败(忽略): key={}, err={}", key, it.message) }
    }

    companion object {
        private const val KEY_PREFIX = "parse:lock:"

        /** 「值还是我写的那个才删」——Redis 官方推荐的 CAS 释放写法。 */
        private val RELEASE_SCRIPT: RedisScript<Long> = DefaultRedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long::class.java,
        )

        /** 抓取一个书签的锁 key。 */
        fun bookmark(pageId: String) = "bookmark:$pageId"

        /** 补投递一条用户书签的解析任务的锁 key。 */
        fun dispatch(userLinkId: String) = "dispatch:$userLinkId"

        /**
         * 一轮活性巡检的锁 key（[taskLabel] 区分不同的巡检任务）。
         *
         * 巡检任务标了 `@Async`，于是 Spring 调度器自带的「同一个任务不重叠」保证就没了：
         * 一轮跑超过调度周期时，下一轮会在同一批候选上再跑一遍（它们的 next_check_at 还没写回），
         * 重复 ping、重复写日志。锁走 Redis 而不是进程内标志，顺带把多实例部署也挡住了。
         */
        fun sweep(taskLabel: String) = "sweep:$taskLabel"

        /**
         * 「这个页面截不出图」的抑制 key。
         *
         * 与 [dispatch] 同一个用法：**不主动释放，靠 TTL 退避**。截图是全系统最贵的一次调用
         * （强制无头、对端 Chrome 全局串行、我方截图池单线程），而 `captureScreenshot` 的
         * 跳过条件是「已经有截图资产」—— 失败时它什么也不写，于是这个条件对失败页面**永远
         * 不成立**，每一轮内容重抓都要为同一个截不出图的页面再付一次 30s。
         *
         * 2026-08-07 查生产：297 个页面里只有 66 个有截图，其余每轮都会重试一次。
         * 成功时调用方会主动释放它（此后由资产存在性接管），所以留下的必然是失败。
         */
        fun screenshot(pageId: String) = "screenshot:$pageId"
    }
}
