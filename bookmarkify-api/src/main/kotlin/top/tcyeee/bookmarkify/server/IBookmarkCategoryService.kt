package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.BookmarkCategory
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.Category
import top.tcyeee.bookmarkify.entity.entity.CategorySource

interface IBookmarkCategoryService : IService<BookmarkCategory> {
    /**
     * 为 canonical 书签生成并保存分类（幂等：先删旧关联再插新）。
     * 失败静默，不抛异常、不影响解析主流程。
     */
    fun categorize(bookmark: BookmarkEntity)

    /** 批量查询多个书签各自命中的分类（避免 N+1）。返回 bookmarkId -> 分类列表。 */
    fun categoriesOf(bookmarkIds: Collection<String>): Map<String, List<Category>>

    /** 幂等替换某书签的全部分类关联（物理删旧 + 插新）。source 标记来源。 */
    fun replaceLinks(bookmarkId: String, categoryIds: List<String>, source: CategorySource)
}
