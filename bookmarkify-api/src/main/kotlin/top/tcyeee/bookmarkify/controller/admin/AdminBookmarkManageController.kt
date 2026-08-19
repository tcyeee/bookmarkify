package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.entity.AppNameSuggestVO
import top.tcyeee.bookmarkify.entity.BookmarkAdminVO
import top.tcyeee.bookmarkify.entity.BookmarkAssetRefetchVO
import top.tcyeee.bookmarkify.entity.BookmarkBasicInfoUpdateParams
import top.tcyeee.bookmarkify.entity.BookmarkCategoriesParams
import top.tcyeee.bookmarkify.entity.BookmarkLivenessVO
import top.tcyeee.bookmarkify.entity.BookmarkRefetchApplyParams
import top.tcyeee.bookmarkify.entity.BookmarkRefetchVO
import top.tcyeee.bookmarkify.entity.BookmarkSearchParams
import top.tcyeee.bookmarkify.entity.CategoryVO
import top.tcyeee.bookmarkify.entity.dto.CollectionMetaResult
import top.tcyeee.bookmarkify.entity.dto.ExtractCollectionLinksParams
import top.tcyeee.bookmarkify.entity.dto.ExtractCollectionLinksResult
import top.tcyeee.bookmarkify.entity.dto.GenerateCollectionMetaParams
import top.tcyeee.bookmarkify.entity.dto.PublishSystemCollectionParams
import top.tcyeee.bookmarkify.entity.dto.SimilarIngestParams
import top.tcyeee.bookmarkify.entity.dto.SimilarSite
import top.tcyeee.bookmarkify.entity.dto.StartCollectionCrawlParams
import top.tcyeee.bookmarkify.entity.dto.StartCollectionCrawlResult
import top.tcyeee.bookmarkify.entity.dto.SystemCollectionVO
import top.tcyeee.bookmarkify.server.admin.IBookmarkAdminService
import top.tcyeee.bookmarkify.utils.StpKit

/**
 * @author tcyeee
 * @date 1/6/26 15:52
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/bookmark")
class AdminBookmarkManageController(
    private val bookmarkAdminService: IBookmarkAdminService,
) {

    // 按条件获取全部的书签信息（带搜索与状态等筛选条件）
    @PostMapping("/all")
    fun getAllBookmarks(@RequestBody params: BookmarkSearchParams): IPage<BookmarkAdminVO> =
        bookmarkAdminService.adminListAll(params)

    // 修改单个书签基础信息（标题/简介/简称），非空字段才会覆盖；简称传空串表示清空
    @PostMapping("/{pageId}/update")
    fun updateBookmark(
        @PathVariable pageId: String, @RequestBody params: BookmarkBasicInfoUpdateParams
    ): BookmarkAdminVO = bookmarkAdminService.adminUpdateBasicInfo(pageId, params)

    // 重新获取：重新解析网站标题与图标（不落库），返回预览数据供前端对比选择
    @PostMapping("/{pageId}/refetch")
    fun refetchBookmark(@PathVariable pageId: String): BookmarkRefetchVO =
        bookmarkAdminService.adminRefetch(pageId)

    // 应用重新获取的结果：按选择采用新标题/新图标并持久化
    @PostMapping("/{pageId}/refetch/apply")
    fun applyRefetchBookmark(
        @PathVariable pageId: String, @RequestBody params: BookmarkRefetchApplyParams
    ): BookmarkAdminVO = bookmarkAdminService.adminApplyRefetch(pageId, params)

    // 手动覆盖式设置某书签的分类
    @PostMapping("/{pageId}/categories")
    fun updateCategories(
        @PathVariable pageId: String, @RequestBody params: BookmarkCategoriesParams
    ): List<CategoryVO> = bookmarkAdminService.adminUpdateCategories(pageId, params.categoryIds)

    // 对某书签重新执行 DeepSeek 自动归类
    @PostMapping("/{pageId}/categorize")
    fun recategorize(@PathVariable pageId: String): List<CategoryVO> =
        bookmarkAdminService.adminRecategorize(pageId)

    // AI 推荐相似网站（仅展示，不入库；返回项带 exists 标记本地是否已收录）
    @PostMapping("/{pageId}/similar")
    fun similar(@PathVariable pageId: String): List<SimilarSite> =
        bookmarkAdminService.adminSimilarSites(pageId)

    // 「一键收录」：异步收录传入的相似网站域名，立即返回；逐站进度经 WebSocket(SIMILAR_INGEST_UPDATE) 推送
    @PostMapping("/{pageId}/similar/ingest")
    fun ingestSimilar(
        @PathVariable pageId: String, @RequestBody params: SimilarIngestParams
    ): Map<String, Any> {
        val adminUid = StpKit.ADMIN.loginIdAsString
        val domains = params.domains.distinct()
        bookmarkAdminService.adminIngestSimilar(adminUid, domains)
        return mapOf("started" to true, "count" to domains.size)
    }

    // DeepSeek 生成书签简称建议（不落库，供前端填入编辑框）
    @PostMapping("/{pageId}/appname/generate")
    fun generateAppName(@PathVariable pageId: String): AppNameSuggestVO =
        AppNameSuggestVO(bookmarkAdminService.adminGenerateAppName(pageId))

    // 删除单个书签信息 (使用POST替换DELETE)
    @PostMapping("/{pageId}/delete")
    fun deleteBookmark(@PathVariable pageId: Long): ResponseEntity<Void> {
        // 实现删除单个书签信息的逻辑
        return ResponseEntity.ok().build()
    }

    // 对某个书签进行活性检测：直接调用 scrapper 重新抓取一次，回传其给出的全部字段，并同步落库 isActivity/parseStatus
    @PostMapping("/{pageId}/liveness")
    fun checkBookmarkLiveness(@PathVariable pageId: String): BookmarkLivenessVO =
        bookmarkAdminService.adminCheckLiveness(pageId)

    // 图片资产重新抓取：只重抓图片，不覆盖标题/简介、不解锁人工锁；本次没抓到则保留原图
    @PostMapping("/{pageId}/assets/refetch")
    fun refetchAssets(@PathVariable pageId: String): BookmarkAssetRefetchVO =
        bookmarkAdminService.adminRefetchAssets(pageId)

    // 「一键更新」：重新抓取网站信息并直接覆盖持久化标题/简介/图标/高清 LOGO，同步落库 isActivity/parseStatus
    @PostMapping("/{pageId}/refresh")
    fun refreshBookmark(@PathVariable pageId: String): BookmarkAdminVO =
        bookmarkAdminService.adminRefresh(pageId)

    /* 书签集管理 */

    // 获取全部系统书签集信息
    @GetMapping("/collections/system")
    fun getAllSystemCollections(): List<SystemCollectionVO> = bookmarkAdminService.adminListSystemCollections()

    // 「AI 批量导出为集合」步骤1：把长文本交给 AI，提取并去重其中的原始链接（仅预览，不落库）
    @PostMapping("/collections/system/ai/extract-links")
    fun extractCollectionLinks(@RequestBody params: ExtractCollectionLinksParams): ExtractCollectionLinksResult =
        ExtractCollectionLinksResult(bookmarkAdminService.adminExtractCollectionLinks(params.text))

    // 步骤2：创建批量抓取任务，立即返回 jobId；每条链接的抓取结果经管理员 WebSocket 逐条推送
    @PostMapping("/collections/system/crawl")
    fun startCollectionCrawl(@RequestBody params: StartCollectionCrawlParams): StartCollectionCrawlResult {
        val urls = params.urls.distinct()
        val jobId = IdUtil.fastUUID()
        bookmarkAdminService.adminCrawlCollectionLinks(StpKit.ADMIN.loginIdAsString, jobId, urls)
        return StartCollectionCrawlResult(jobId, urls.size)
    }

    // 步骤3：根据已抓取的书签生成集合标题/描述建议（仅预览，不落库）
    @PostMapping("/collections/system/ai/generate-meta")
    fun generateCollectionMeta(@RequestBody params: GenerateCollectionMetaParams): CollectionMetaResult =
        bookmarkAdminService.adminGenerateCollectionMeta(params.bookmarks)

    // 发布一条系统书签集（上面三步确认后的最终提交）
    @PostMapping("/collections/system/publish")
    fun publishSystemCollection(@RequestBody params: PublishSystemCollectionParams): SystemCollectionVO =
        bookmarkAdminService.adminPublishSystemCollection(StpKit.ADMIN.loginIdAsString, params)

    // 修改一条系统书签集：标题/描述/包含的书签整体覆盖 (使用POST替换PUT)
    @PostMapping("/collections/system/{collectionId}/update")
    fun updateSystemCollection(
        @PathVariable collectionId: String, @RequestBody params: PublishSystemCollectionParams
    ): SystemCollectionVO = bookmarkAdminService.adminUpdateSystemCollection(collectionId, params)

    // 获取全部的用户书签集
    @GetMapping("/collections/user")
    fun getAllUserCollections(): ResponseEntity<List<Any>> {
        // 实现获取全部用户书签集的逻辑
        return ResponseEntity.ok(listOf())
    }

    // 修改一条用户书签集 (使用POST替换PUT)
    @PostMapping("/collections/user/{collectionId}/update")
    fun updateUserCollection(
        @PathVariable collectionId: Long, @RequestBody collectionData: Map<String, Any>
    ): ResponseEntity<Any> {
        // 实现修改用户书签集的逻辑
        return ResponseEntity.ok().build()
    }
}
