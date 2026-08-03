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
import top.tcyeee.bookmarkify.entity.entity.BookmarkPingLogEntity
import top.tcyeee.bookmarkify.entity.entity.BookmarkSweepLogEntity
import top.tcyeee.bookmarkify.mapper.BookmarkPingLogMapper
import top.tcyeee.bookmarkify.mapper.BookmarkSweepLogMapper
import top.tcyeee.bookmarkify.server.IBookmarkPingLogService
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 书签活性检查日志 Service 实现
 */
@Service
class BookmarkPingLogServiceImpl(
    private val sweepLogMapper: BookmarkSweepLogMapper,
) : IBookmarkPingLogService, ServiceImpl<BookmarkPingLogMapper, BookmarkPingLogEntity>() {

    override fun adminListAll(params: BookmarkPingLogSearchParams): IPage<BookmarkPingLogVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { BookmarkPingLogVO(it) }

    override fun adminListSweeps(params: BookmarkSweepLogSearchParams): IPage<BookmarkSweepLogEntity> =
        sweepLogMapper.selectPage(params.toPage(), params.toWrapper())

    override fun adminSweepHealth(windowHours: Int): SweepHealthVO {
        val since = LocalDateTime.now().minusHours(windowHours.toLong())
        // 窗口内的轮次全取出来在内存里统计：一天最多几十行（两个小时级巡检 + 一个日级），
        // 分成三条聚合 SQL 反而更啰嗦
        val rounds = sweepLogMapper.selectList(
            KtQueryWrapper(BookmarkSweepLogEntity::class.java)
                .ge(BookmarkSweepLogEntity::createTime, since)
                .orderByDesc(BookmarkSweepLogEntity::createTime)
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
                KtQueryWrapper(BookmarkSweepLogEntity::class.java)
                    .orderByDesc(BookmarkSweepLogEntity::createTime)
                    .last("LIMIT 1")
            ).firstOrNull()?.createTime,
        )
    }

    override fun purgeExpired(): Int {
        val expireBefore = LocalDateTimeUtil.offset(LocalDateTime.now(), -RETENTION_DAYS, ChronoUnit.DAYS)
        val deleted = baseMapper.delete(
            KtQueryWrapper(BookmarkPingLogEntity::class.java).lt(BookmarkPingLogEntity::createTime, expireBefore)
        )
        if (deleted > 0) log.warn("[purgeExpired] 已清理 $deleted 条 ${RETENTION_DAYS} 天前的活性探测日志")

        // 轮次日志同周期清理。它一天只有几十行，膨胀压力远小于探测日志，
        // 但没人清理的话同样会一年年攒下去
        val sweeps = sweepLogMapper.delete(
            KtQueryWrapper(BookmarkSweepLogEntity::class.java).lt(BookmarkSweepLogEntity::createTime, expireBefore)
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
