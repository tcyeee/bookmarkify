package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.po.BookmarkTag
import top.tcyeee.bookmarkify.mapper.BookmarkTagMapper
import top.tcyeee.bookmarkify.server.IBookmarkTagService

/**
 * @author tcyeee
 * @date 3/11/24 17:35
 */
@Service
class BookmarkTagServiceImpl : IBookmarkTagService, ServiceImpl<BookmarkTagMapper, BookmarkTag>()