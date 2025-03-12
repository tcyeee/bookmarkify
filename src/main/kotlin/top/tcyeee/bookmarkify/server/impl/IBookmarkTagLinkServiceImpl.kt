package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.po.BookmarkTagLink
import top.tcyeee.bookmarkify.mapper.BookmarkTagLinkMapper
import top.tcyeee.bookmarkify.server.IBookmarkTagLinkService


/**
 * @author tcyeee
 * @date 3/11/24 17:41
 */
@Service
class IBookmarkTagLinkServiceImpl : IBookmarkTagLinkService, ServiceImpl<BookmarkTagLinkMapper, BookmarkTagLink>()
