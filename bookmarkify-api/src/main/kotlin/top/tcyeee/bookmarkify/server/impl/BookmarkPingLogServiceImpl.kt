package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.BookmarkPingLogSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkPingLogVO
import top.tcyeee.bookmarkify.entity.entity.BookmarkPingLogEntity
import top.tcyeee.bookmarkify.mapper.BookmarkPingLogMapper
import top.tcyeee.bookmarkify.server.IBookmarkPingLogService

/**
 * 书签活性检查日志 Service 实现
 */
@Service
class BookmarkPingLogServiceImpl :
    IBookmarkPingLogService, ServiceImpl<BookmarkPingLogMapper, BookmarkPingLogEntity>() {

    override fun adminListAll(params: BookmarkPingLogSearchParams): IPage<BookmarkPingLogVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { BookmarkPingLogVO(it) }
}
