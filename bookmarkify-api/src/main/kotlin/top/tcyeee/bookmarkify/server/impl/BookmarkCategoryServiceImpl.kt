package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.entity.dto.CategoryCandidate
import top.tcyeee.bookmarkify.entity.entity.BookmarkCategory
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.mapper.BookmarkCategoryMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkCategoryService
import top.tcyeee.bookmarkify.server.IWebsiteCategoryService

@Service
class BookmarkCategoryServiceImpl(
    private val websiteCategoryService: IWebsiteCategoryService,
    private val apiService: IApiService,
) : IBookmarkCategoryService, ServiceImpl<BookmarkCategoryMapper, BookmarkCategory>() {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun categorize(bookmark: BookmarkEntity) {
        runCatching {
            val categories = websiteCategoryService.activeCandidates()
            if (categories.isEmpty()) {
                log.debug("[categorize] 字典为空，跳过: bookmarkId=${bookmark.id}")
                return
            }
            val candidates = categories.map { CategoryCandidate(it.slug, it.name, it.description) }
            val slugs = apiService.inferCategories(
                bookmark.title, bookmark.description, bookmark.urlHost, candidates,
            )
            if (slugs.isEmpty()) {
                log.debug("[categorize] DeepSeek 未返回有效分类: bookmarkId=${bookmark.id}")
                return
            }
            val slugToId = categories.associate { it.slug to it.id }
            val categoryIds = slugs.mapNotNull { slugToId[it] }
            if (categoryIds.isEmpty()) return
            replaceLinks(bookmark.id, categoryIds)
            log.debug("[categorize] 分类完成: bookmarkId=${bookmark.id}, slugs=$slugs")
        }.onFailure {
            log.warn("[categorize] 分类失败(忽略): bookmarkId=${bookmark.id}, err=${it.message}")
        }
    }

    /** 幂等替换：物理删除旧关联，再插入新关联（避开 unique 约束与软删冲突） */
    @Transactional
    fun replaceLinks(bookmarkId: String, categoryIds: List<String>) {
        ktUpdate().eq(BookmarkCategory::bookmarkId, bookmarkId).remove()
        saveBatch(categoryIds.map { BookmarkCategory(bookmarkId, it) })
    }
}
