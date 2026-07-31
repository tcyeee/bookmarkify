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

    /**
     * 后台「重新 AI 归类」：让 DeepSeek 自由提议分类，**词表里没有的会被建进 `category` 字典**，
     * 然后照常替换该书签的分类关联。
     *
     * 与 [categorize] 分成两个方法而不是加个开关：能写字典是一项危险得多的权限，自动抓取链路
     * 一次都不该碰到它。词表为空时 [categorize] 直接跳过，而这条正是用来把空词表填起来的。
     *
     * @return 该书签最终命中的分类；AI 无结果时返回空列表且不改动既有关联。
     */
    fun categorizeAllowingNew(bookmark: BookmarkEntity): List<Category>

    /** 批量查询多个书签各自命中的分类（避免 N+1）。返回 bookmarkId -> 分类列表。 */
    fun categoriesOf(bookmarkIds: Collection<String>): Map<String, List<Category>>

    /** 幂等替换某书签的全部分类关联（物理删旧 + 插新）。source 标记来源。 */
    fun replaceLinks(bookmarkId: String, categoryIds: List<String>, source: CategorySource)
}
