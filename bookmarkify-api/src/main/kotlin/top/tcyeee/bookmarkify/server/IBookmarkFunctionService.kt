package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.BookmarkFunctionEntity

interface IBookmarkFunctionService : IService<BookmarkFunctionEntity> {
    fun findByUid(uid: String):List<BookmarkFunctionEntity>
}

