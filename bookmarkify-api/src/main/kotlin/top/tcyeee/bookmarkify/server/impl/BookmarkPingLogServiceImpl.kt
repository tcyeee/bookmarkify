package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.date.LocalDateTimeUtil
import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.BookmarkPingLogSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkPingLogVO
import top.tcyeee.bookmarkify.entity.BookmarkSweepLogSearchParams
import top.tcyeee.bookmarkify.entity.SweepHealthVO
import top.tcyeee.bookmarkify.entity.entity.PagePingLogEntity
import top.tcyeee.bookmarkify.entity.entity.SweepLogEntity
import top.tcyeee.bookmarkify.mapper.PageMapper
import top.tcyeee.bookmarkify.mapper.PagePingLogMapper
import top.tcyeee.bookmarkify.mapper.SweepLogMapper
import top.tcyeee.bookmarkify.server.IBookmarkPingLogService
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 书签活性检查日志 Service 实现
 */
@Service
class BookmarkPingLogServiceImpl(
    private val sweepLogMapper: SweepLogMapper,
    private val pageMapper: PageMapper,
) : IBookmarkPingLogService, ServiceImpl<PagePingLogMapper, PagePingLogEntity>() {

    override fun adminListAll(params: BookmarkPingLogSearchParams): IPage<BookmarkPingLogVO> {
        val page = baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { BookmarkPingLogVO(it) }

        // 完整地址一次查询补齐整页 —— 逐行去查就是 N+1，而按轮次下钻时一页默认 50 行。
        // 日志表只存 host，只有 host 时同一域名下的几十条深链在列表里长得一模一样。
        // 探测过后页面被删是正常的（用户取消收藏 + 无人引用后清理），查不到就留空，不猜。
        val pageIds = page.records.mapTo(HashSet()) { it.pageId }
        if (pageIds.isNotEmpty()) {
            val urlById = pageMapper.selectByIds(pageIds).associate { it.id to it.rawUrl }
            page.records.forEach { it.url = urlById[it.pageId] }
        }
        return page
    }

    override fun adminListSweeps(params: BookmarkSweepLogSearchParams): IPage<SweepLogEntity> =
        sweepLogMapper.selectPage(params.toPage(), params.toWrapper())

    override fun adminSweepHealth(windowHours: Int): SweepHealthVO {
        val since = LocalDateTime.now().minusHours(windowHours.toLong())
        // 窗口内的轮次全取出来在内存里统计：一天最多几十行（两个小时级巡检 + 一个日级），
        // 分成三条聚合 SQL 反而更啰嗦
        val rounds = sweepLogMapper.selectList(
            KtQueryWrapper(SweepLogEntity::class.java)
                .ge(SweepLogEntity::createTime, since)
                .orderByDesc(SweepLogEntity::createTime)
        )
        val breakers = rounds.filter { !it.breakerReason.isNullOrBlank() }
        return SweepHealthVO(
            windowHours = windowHours,
            roundCount = rounds.size,
            breakerCount = breakers.size,
            deferredParse = rounds.sumOf { it.deferredParse },
            latestBreaker = breakers.firstOrNull(),
            // 刻意**不**限制在窗口内：窗口内一轮都没有，恰恰是最该报警的情形，
            // 这时更要知道"最后一次是什么时候"
            lastRoundAt = rounds.firstOrNull()?.createTime ?: sweepLogMapper.selectList(
                KtQueryWrapper(SweepLogEntity::class.java)
                    .orderByDesc(SweepLogEntity::createTime)
                    .last("LIMIT 1")
            ).firstOrNull()?.createTime,
        )
    }

    override fun purgeExpired(): Int {
        val expireBefore = LocalDateTimeUtil.offset(LocalDateTime.now(), -RETENTION_DAYS, ChronoUnit.DAYS)
        val deleted = baseMapper.delete(
            KtQueryWrapper(PagePingLogEntity::class.java).lt(PagePingLogEntity::createTime, expireBefore)
        )
        if (deleted > 0) log.warn("[purgeExpired] 已清理 $deleted 条 ${RETENTION_DAYS} 天前的活性探测日志")

        // 轮次日志同周期清理。它一天只有几十行，膨胀压力远小于探测日志，
        // 但没人清理的话同样会一年年攒下去
        val sweeps = sweepLogMapper.delete(
            KtQueryWrapper(SweepLogEntity::class.java).lt(SweepLogEntity::createTime, expireBefore)
        )
        if (sweeps > 0) log.warn("[purgeExpired] 已清理 $sweeps 条 ${RETENTION_DAYS} 天前的巡检轮次日志")
        return deleted + sweeps
    }

    companion object {
        /**
         * 探测日志保留天数。
         *
         * 90 天足够覆盖「这个站点最近是不是一直在抽」这类排查，而按每小时最多 250 行算，
         * 表也就稳定在 50 万行量级，不会一年年膨胀下去。
         */
        private const val RETENTION_DAYS = 90L
    }
}
