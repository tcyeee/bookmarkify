package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.BookmarkCategory
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity

interface IBookmarkCategoryService : IService<BookmarkCategory> {
    /**
     * 为 canonical 书签生成并保存分类（幂等：先删旧关联再插新）。
     * 失败静默，不抛异常、不影响解析主流程。
     */
    fun categorize(bookmark: BookmarkEntity)
}
