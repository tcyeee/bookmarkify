package top.tcyeee.bookmarkify.server.liveness

import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import top.tcyeee.bookmarkify.mapper.PageMapper
import top.tcyeee.bookmarkify.server.IBookmarkLivenessConfigService
import java.time.LocalDateTime

/**
 * 巡检调度列（[PageEntity.lastCheckAt] / [PageEntity.nextCheckAt] / [PageEntity.consecutiveFail] /
 * [PageEntity.lastParseAt]）的**唯一**写入口。
 *
 * ## 为什么它是一个独立组件而不是某个 Service 的私有方法
 *
 * 这几列有两个写入方，而它们分属两条互不相干的链路：
 *
 * - **巡检链路**（[LivenessSweepService]）—— 一次纯探测的结论落库；
 * - **解析链路**（`BookmarkServiceImpl.markParseSucceeded` / `markParseUnreachable`）——
 *   一次抓取的终态落库。
 *
 * 拆分 `BookmarkServiceImpl` 之前，两者共用同一个私有扩展函数，所以"取法一致"是免费的。
 * 拆开之后如果各写一份，就会退化成一条只存在于注释里的约定 —— 而这几列的一致性没有任何
 * 症状来提示违反：`consecutiveFail` 的两种取法一旦分叉，表现是某一类记录永远够不到判失活
 * 或归档门槛，于是每轮都被重新选中、永久占据 `ORDER BY next_check_at ASC LIMIT n` 的队头，
 * 把真正该复查的记录饿死。候选数看起来一直是满的，日志一行不报。
 *
 * 所以这里刻意做成一个共享组件：两条链路调的是同一段代码，不是同一段注释。
 */
@Component
class PageScheduleWriter(
    private val pageMapper: PageMapper,
    private val livenessConfigService: IBookmarkLivenessConfigService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 按本次结论推进调度列，**只改内存**不落库 —— 调用方往往还要在同一次 update 里带上
     * title / 资产等其它字段，强行在这里写会变成两次数据库往返。
     *
     * 刻意不碰 `updateTime`：那是「记录最近修改时间」，由真正改了内容的调用方自己写。
     *
     * 配置在这里自己读：[IBookmarkLivenessConfigService.getConfig] 走进程内缓存，逐条调用不再有代价。
     * 此前它是一个「默认自己读、批量巡检必须显式传本轮那份」的参数——省的是几百次
     * `system_config` 往返，代价是一条编译器不管、违反了也没有任何症状的口头约定。
     *
     * @param directlyProbed 本轮结论是否来自对**这个页面**的实际探测。站点层短路出来的 DEAD 是
     *   上一轮结论的复用，不是新证据 —— 它已经被排除在熔断样本之外
     *   （见 [LivenessSweepService]），失败计数这一侧曾经一直漏着，理由完全相同。
     */
    fun advance(
        page: PageEntity,
        outcome: PingOutcome,
        contentRefreshed: Boolean = false,
        directlyProbed: Boolean = true,
    ) {
        val config = livenessConfigService.getConfig()
        val now = LocalDateTime.now()
        page.lastCheckAt = now
        if (contentRefreshed) page.lastParseAt = now
        page.consecutiveFail = when {
            outcome == PingOutcome.ALIVE -> 0
            // 没探测就没有新证据。少了这一条，一个域名被误判死亡后，其下**所有**页面
            // 会在 10 个退避周期里被逐条推进 ARCHIVED —— 而归档没有自动出口，
            // 一次站点级误判就这样静默吃掉整个域名的书签
            !directlyProbed -> page.consecutiveFail
            outcome == PingOutcome.DEAD -> page.consecutiveFail + 1
            // 无结论是我方链路的问题，不能记在站点账上：否则一次抓取服务故障就把全表推到
            // 退避曲线末端，之后半个月都不再复查
            else -> page.consecutiveFail
        }
        page.nextCheckAt = LivenessPolicy.nextCheckAt(
            now = now,
            outcome = outcome,
            consecutiveFail = page.consecutiveFail,
            config = config,
        )
    }

    /** 解析成功后推进调度：内容确实被刷新了。 */
    fun advanceAfterParseSuccess(page: PageEntity) = advance(page, PingOutcome.ALIVE, contentRefreshed = true)

    /**
     * 解析判定站点不可达后推进调度：计入连续失败，走指数退避。
     *
     * 这里**不做归档**：归档是「候选池该不该继续包含这条记录」的调度决定，归巡检
     * （`LivenessSweepService.persistProbeResult`）负责。解析失败而 ping 仍然通得过，
     * 说明站点活着、只是我方抓不动，那种情况值得继续按最长退避间隔偶尔重试，而不是就地判死。
     */
    fun advanceAfterParseFailure(page: PageEntity) = advance(page, PingOutcome.DEAD)

    /**
     * 把调度游标往前推一小段，**只动游标**，不碰状态、不碰失败计数。直接落库。
     *
     * ## 这个方法存在的全部理由
     *
     * 巡检在决定"这条要重新抓取"时刻意不写调度列，把它交给解析链路（成功回到正常周期、
     * 失败继续退避）——这个分工本身是对的，抢着写只会被覆盖，还会掩盖真实的失败次数。
     * 但它有个前提：**解析链路一定会写**。而它有好几条出口不写：
     *
     * - `parseByApi` 判定 `isScrapperUnavailable`（E307）时原样 `return`，一个字段都不落库；
     * - `parseBookmark` 拿不到解析锁时直接返回；
     * - 监听器里的 `runCatching` 吞掉任意异常。
     *
     * 任一发生，这条记录的 `next_check_at` 就停在一个已经过去的时刻。而候选查询是
     * `ORDER BY COALESCE(next_check_at, epoch) ASC LIMIT n` —— 它会**永久占据队头**，把后面的
     * 记录全部饿死。`retryUnreachableBookmarks` 的批量只有 50，凑够 50 条这样的记录，
     * 这个任务就再也探不到第 51 条。这与 `drainStuckLoading` 用 `dispatch_attempts`
     * 解决的是同一类事故，只是巡检这侧一直没有对应的机制。
     *
     * 取一个短间隔（[PROTECT_HOURS]）而不是完整周期：正常情况下解析链路马上就会用真实结论
     * 覆盖它，这个值只在上面那几条异常出口上生效，那时我们希望它尽快被重试。
     */
    fun protect(pageId: String) {
        val now = LocalDateTime.now()
        // 刻意不写 updateTime：这里没有改动记录的任何业务内容，只是挪了一下巡检游标
        runCatching {
            pageMapper.update(
                null,
                KtUpdateWrapper(PageEntity::class.java)
                    .eq(PageEntity::id, pageId)
                    .set(PageEntity::lastCheckAt, now)
                    .set(PageEntity::nextCheckAt, now.plusHours(PROTECT_HOURS))
            )
        }.onFailure {
            log.warn("[protectSchedule] 保护性游标写入失败: pageId=$pageId, err=${it.message}")
        }
    }

    companion object {
        /**
         * 巡检投递重新抓取前写入的保护性游标间隔，见 [protect]。
         *
         * 取 1h（与调度周期同量级）而不是更短：解析链路正常会立刻用真实结论覆盖它，
         * 这个值只在那几条不落库的异常出口上生效，太短会让一个持续故障变成每分钟重试。
         */
        const val PROTECT_HOURS = 1L
    }
}
