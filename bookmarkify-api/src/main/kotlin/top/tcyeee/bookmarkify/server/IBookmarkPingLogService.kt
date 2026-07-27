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
}
