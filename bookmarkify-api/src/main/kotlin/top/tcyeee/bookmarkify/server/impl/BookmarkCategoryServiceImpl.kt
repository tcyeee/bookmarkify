package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import top.tcyeee.bookmarkify.entity.CategorySaveParams
import top.tcyeee.bookmarkify.entity.dto.CategoryCandidate
import top.tcyeee.bookmarkify.entity.entity.BookmarkCategory
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.Category
import top.tcyeee.bookmarkify.entity.entity.CategorySource
import top.tcyeee.bookmarkify.mapper.BookmarkCategoryMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkCategoryService
import top.tcyeee.bookmarkify.server.ICategoryService

@Service
class BookmarkCategoryServiceImpl(
    private val categoryService: ICategoryService,
    private val apiService: IApiService,
    transactionManager: PlatformTransactionManager,
) : IBookmarkCategoryService, ServiceImpl<BookmarkCategoryMapper, BookmarkCategory>() {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(transactionManager)

    override fun categorize(bookmark: BookmarkEntity) {
        runCatching {
            val categories = categoryService.activeCandidates()
            if (categories.isEmpty()) {
                logger.debug("[categorize] 字典为空，跳过: bookmarkId=${bookmark.id}")
                return
            }
            val candidates = categories.map { CategoryCandidate(it.slug, it.name, it.description) }
            val slugs = apiService.inferCategories(
                bookmark.title, bookmark.description, bookmark.urlHost, candidates,
            )
            if (slugs.isEmpty()) {
                logger.debug("[categorize] DeepSeek 未返回有效分类: bookmarkId=${bookmark.id}")
                return
            }
            val slugToId = categories.associate { it.slug to it.id }
            val categoryIds = slugs.mapNotNull { slugToId[it] }
            if (categoryIds.isEmpty()) return
            replaceLinks(bookmark.id, categoryIds, CategorySource.DEEPSEEK)
            logger.debug("[categorize] 分类完成: bookmarkId=${bookmark.id}, slugs=$slugs")
        }.onFailure {
            logger.warn("[categorize] 分类失败(忽略): bookmarkId=${bookmark.id}, err=${it.message}")
        }
    }

    override fun categorizeAllowingNew(bookmark: BookmarkEntity): List<Category> {
        val existing = categoryService.listAll()
        val proposals = apiService.proposeCategories(
            bookmark.title,
            bookmark.description,
            bookmark.urlHost,
            existing.map { CategoryCandidate(it.slug, it.name, it.description) },
        )
        if (proposals.isEmpty()) {
            logger.debug("[categorizeAllowingNew] DeepSeek 未返回分类: bookmarkId=${bookmark.id}")
            return emptyList()
        }

        // slug 大小写不敏感地对齐既有词表：模型复用旧词时偶尔会改大小写，不归一就会建出重复分类
        val bySlug = existing.associateBy { it.slug.lowercase() }
        var nextSort = (existing.maxOfOrNull { it.sort } ?: 0) + 1
        val resolved = proposals.map { p ->
            bySlug[p.slug] ?: categoryService.saveCategory(
                CategorySaveParams(slug = p.slug, name = p.name, sort = nextSort++),
            ).also { logger.info("[categorizeAllowingNew] 新建分类: slug=${it.slug}, name=${it.name}") }
        }

        replaceLinks(bookmark.id, resolved.map { it.id }, CategorySource.DEEPSEEK)
        logger.debug(
            "[categorizeAllowingNew] 分类完成: bookmarkId=${bookmark.id}, slugs=${resolved.map { it.slug }}",
        )
        return resolved
    }

    override fun categoriesOf(bookmarkIds: Collection<String>): Map<String, List<Category>> {
        if (bookmarkIds.isEmpty()) return emptyMap()
        val links = ktQuery()
            .`in`(BookmarkCategory::bookmarkId, bookmarkIds)
            .eq(BookmarkCategory::deleted, false)
            .list()
        if (links.isEmpty()) return emptyMap()
        val catById = categoryService
            .listByIds(links.map { it.categoryId }.distinct())
            .associateBy { it.id }
        return links.groupBy { it.bookmarkId }
            .mapValues { (_, ls) -> ls.mapNotNull { catById[it.categoryId] } }
    }

    /** 幂等替换：物理删除旧关联，再插入新关联（避开 unique 约束与软删冲突）。
     *  用 TransactionTemplate 包住删除+插入，保证原子（@Transactional 在同 bean 自调用下会失效）。 */
    override fun replaceLinks(bookmarkId: String, categoryIds: List<String>, source: CategorySource) {
        txTemplate.execute {
            ktUpdate().eq(BookmarkCategory::bookmarkId, bookmarkId).remove()
            saveBatch(categoryIds.map {
                BookmarkCategory(bookmarkId = bookmarkId, categoryId = it, source = source)
            })
        }
    }
}
