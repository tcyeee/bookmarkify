package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.BookmarkPingLogSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkPingLogVO
import top.tcyeee.bookmarkify.entity.entity.BookmarkPingLogEntity

/**
 * 书签活性检查日志 Service
 */
interface IBookmarkPingLogService : IService<BookmarkPingLogEntity> {
    fun adminListAll(params: BookmarkPingLogSearchParams): IPage<BookmarkPingLogVO>

    /**
     * 删除超过保留期的探测日志，返回删除条数。
     *
     * 这张表只增不减：每小时最多写 250 行、约 6k/天、220 万/年，而它的用途是排查最近的异常，
     * 半年前某个域名 ping 过一次没有任何价值。
     */
    fun purgeExpired(): Int
}
