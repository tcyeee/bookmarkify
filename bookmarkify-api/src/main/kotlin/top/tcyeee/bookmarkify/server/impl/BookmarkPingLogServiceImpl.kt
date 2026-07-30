package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.date.LocalDateTimeUtil
import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.BookmarkPingLogSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkPingLogVO
import top.tcyeee.bookmarkify.entity.entity.BookmarkPingLogEntity
import top.tcyeee.bookmarkify.mapper.BookmarkPingLogMapper
import top.tcyeee.bookmarkify.server.IBookmarkPingLogService
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 书签活性检查日志 Service 实现
 */
@Service
class BookmarkPingLogServiceImpl :
    IBookmarkPingLogService, ServiceImpl<BookmarkPingLogMapper, BookmarkPingLogEntity>() {

    override fun adminListAll(params: BookmarkPingLogSearchParams): IPage<BookmarkPingLogVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { BookmarkPingLogVO(it) }

    override fun purgeExpired(): Int {
        val expireBefore = LocalDateTimeUtil.offset(LocalDateTime.now(), -RETENTION_DAYS, ChronoUnit.DAYS)
        val deleted = baseMapper.delete(
            KtQueryWrapper(BookmarkPingLogEntity::class.java).lt(BookmarkPingLogEntity::createTime, expireBefore)
        )
        if (deleted > 0) log.warn("[purgeExpired] 已清理 $deleted 条 ${RETENTION_DAYS} 天前的活性探测日志")
        return deleted
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
