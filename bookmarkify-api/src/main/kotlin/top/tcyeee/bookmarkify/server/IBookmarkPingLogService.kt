package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.BookmarkPingLogSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkPingLogVO
import top.tcyeee.bookmarkify.entity.BookmarkSweepLogSearchParams
import top.tcyeee.bookmarkify.entity.SweepHealthVO
import top.tcyeee.bookmarkify.entity.entity.PagePingLogEntity
import top.tcyeee.bookmarkify.entity.entity.SweepLogEntity

/**
 * 书签活性检查日志 Service
 */
interface IBookmarkPingLogService : IService<PagePingLogEntity> {
    fun adminListAll(params: BookmarkPingLogSearchParams): IPage<BookmarkPingLogVO>

    /**
     * 巡检轮次列表（一轮一行）。
     *
     * 与 [adminListAll] 是两个粒度：那边一次探测一行，回答"这个域名最近怎么样"；
     * 这边一轮一行，回答"巡检系统本身怎么样"——有没有被熔断、积压追不追得上、
     * 有多少重新抓取因为队列拥堵被推迟。
     */
    fun adminListSweeps(params: BookmarkSweepLogSearchParams): IPage<SweepLogEntity>

    /**
     * 巡检健康摘要，供后台常驻告警条使用。
     *
     * 只回答三个问题，都不需要翻明细：最近这段时间**熔断过几次**、最近一次熔断的现场是什么、
     * 以及**巡检本身还在不在跑**。最后一条同样重要且更隐蔽 —— 调度线程卡死、巡检锁因故
     * 一直没释放的时候，一次熔断都不会有，但整套活性检测已经停了。只看熔断次数看不出来。
     */
    fun adminSweepHealth(windowHours: Int): SweepHealthVO

    /**
     * 删除超过保留期的探测日志与巡检轮次日志，返回删除条数。
     *
     * 探测日志只增不减：每小时最多写 250 行、约 6k/天、220 万/年，而它的用途是排查最近的异常，
     * 半年前某个域名 ping 过一次没有任何价值。轮次日志一天只有几十行，同周期清理即可。
     */
    fun purgeExpired(): Int
}
