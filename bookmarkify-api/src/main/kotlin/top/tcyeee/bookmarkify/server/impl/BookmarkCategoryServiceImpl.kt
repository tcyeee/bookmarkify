package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import top.tcyeee.bookmarkify.entity.CategorySaveParams
import top.tcyeee.bookmarkify.entity.dto.CategoryCandidate
import top.tcyeee.bookmarkify.entity.entity.PageCategory
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.entity.Category
import top.tcyeee.bookmarkify.entity.entity.CategorySource
import top.tcyeee.bookmarkify.mapper.PageCategoryMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkCategoryService
import top.tcyeee.bookmarkify.server.ICategoryService

@Service
class BookmarkCategoryServiceImpl(
    private val categoryService: ICategoryService,
    private val apiService: IApiService,
    transactionManager: PlatformTransactionManager,
) : IBookmarkCategoryService, ServiceImpl<PageCategoryMapper, PageCategory>() {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val txTemplate = TransactionTemplate(transactionManager)

    override fun categorize(bookmark: PageEntity) {
        runCatching {
            val categories = categoryService.activeCandidates()
            // 候选只有 0/1 个时结果是确定的（0 个没得选，1 个必然是它），DeepSeek 不可能给出
            // 更多信息量——调用它只是白烧一次网络往返和 token。词表只有 1 条时直接判给这一条。
            if (categories.size <= 1) {
                logger.debug("[categorize] 候选分类数=${categories.size}，跳过 DeepSeek: pageId=${bookmark.id}")
                categories.singleOrNull()?.let { replaceLinks(bookmark.id, listOf(it.id), CategorySource.DEEPSEEK) }
                return
            }
            val candidates = categories.map { CategoryCandidate(it.slug, it.name, it.description) }
            val slugs = apiService.inferCategories(
                bookmark.title, bookmark.description, bookmark.urlHost, candidates,
            )
            if (slugs.isEmpty()) {
                logger.debug("[categorize] DeepSeek 未返回有效分类: pageId=${bookmark.id}")
                return
            }
            val slugToId = categories.associate { it.slug to it.id }
            val categoryIds = slugs.mapNotNull { slugToId[it] }
            if (categoryIds.isEmpty()) return
            replaceLinks(bookmark.id, categoryIds, CategorySource.DEEPSEEK)
            logger.debug("[categorize] 分类完成: pageId=${bookmark.id}, slugs=$slugs")
        }.onFailure {
            logger.warn("[categorize] 分类失败(忽略): pageId=${bookmark.id}, err=${it.message}")
        }
    }

    override fun categorizeAllowingNew(bookmark: PageEntity): List<Category> {
        val existing = categoryService.listAll()
        val proposals = apiService.proposeCategories(
            bookmark.title,
            bookmark.description,
            bookmark.urlHost,
            existing.map { CategoryCandidate(it.slug, it.name, it.description) },
        )
        if (proposals.isEmpty()) {
            logger.debug("[categorizeAllowingNew] DeepSeek 未返回分类: pageId=${bookmark.id}")
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
            "[categorizeAllowingNew] 分类完成: pageId=${bookmark.id}, slugs=${resolved.map { it.slug }}",
        )
        return resolved
    }

    override fun categoriesOf(pageIds: Collection<String>): Map<String, List<Category>> {
        if (pageIds.isEmpty()) return emptyMap()
        val links = ktQuery()
            .`in`(PageCategory::pageId, pageIds)
            .eq(PageCategory::deleted, false)
            .list()
        if (links.isEmpty()) return emptyMap()
        val catById = categoryService
            .listByIds(links.map { it.categoryId }.distinct())
            .associateBy { it.id }
        return links.groupBy { it.pageId }
            .mapValues { (_, ls) -> ls.mapNotNull { catById[it.categoryId] } }
    }

    /** 幂等替换：物理删除旧关联，再插入新关联（避开 unique 约束与软删冲突）。
     *  用 TransactionTemplate 包住删除+插入，保证原子（@Transactional 在同 bean 自调用下会失效）。 */
    override fun replaceLinks(pageId: String, categoryIds: List<String>, source: CategorySource) {
        txTemplate.execute {
            ktUpdate().eq(PageCategory::pageId, pageId).remove()
            saveBatch(categoryIds.map {
                PageCategory(pageId = pageId, categoryId = it, source = source)
            })
        }
    }
}
