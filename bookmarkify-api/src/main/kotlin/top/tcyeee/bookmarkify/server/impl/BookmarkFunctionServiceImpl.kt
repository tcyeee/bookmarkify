package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.BookmarkFunctionEntity
import top.tcyeee.bookmarkify.mapper.BookmarkFunctionMapper
import top.tcyeee.bookmarkify.server.IBookmarkFunctionService

@Service
class BookmarkFunctionServiceImpl : IBookmarkFunctionService,
    ServiceImpl<BookmarkFunctionMapper, BookmarkFunctionEntity>() {
    override fun findByUid(uid: String): List<BookmarkFunctionEntity> =
        ktQuery().eq(BookmarkFunctionEntity::uid, uid).list()
}

