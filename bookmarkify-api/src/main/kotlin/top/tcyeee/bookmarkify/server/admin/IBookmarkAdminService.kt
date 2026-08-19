package top.tcyeee.bookmarkify.server.admin

import com.baomidou.mybatisplus.core.metadata.IPage
import top.tcyeee.bookmarkify.entity.BookmarkAdminVO
import top.tcyeee.bookmarkify.entity.BookmarkAssetRefetchVO
import top.tcyeee.bookmarkify.entity.BookmarkBasicInfoUpdateParams
import top.tcyeee.bookmarkify.entity.BookmarkLivenessVO
import top.tcyeee.bookmarkify.entity.BookmarkRefetchApplyParams
import top.tcyeee.bookmarkify.entity.BookmarkRefetchVO
import top.tcyeee.bookmarkify.entity.BookmarkSearchParams
import top.tcyeee.bookmarkify.entity.CategoryVO
import top.tcyeee.bookmarkify.entity.dto.CollectionMetaResult
import top.tcyeee.bookmarkify.entity.dto.PublishSystemCollectionParams
import top.tcyeee.bookmarkify.entity.dto.SimilarSite
import top.tcyeee.bookmarkify.entity.dto.SystemCollectionVO
import top.tcyeee.bookmarkify.entity.dto.CollectionBookmark
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse

/**
 * 管理后台对**书签（page 层）**的全部操作。
 *
 * ## 为什么它和 `IBookmarkService` 分开
 *
 * 这批方法此前住在 `BookmarkServiceImpl` 里，与「用户添加书签」「批量导入」「异步解析」共用
 * 一个 2881 行的类。它们之间没有共享状态，只有共享的私有工具方法 —— 而那正是问题：
 * 后台的一次改动（比如给「一键更新」加一个开关）与交互式加书签主链路处在同一个改动半径里，
 * 没有任何东西提示这条边界存在。
 *
 * 拆开之后关系变成单向：本服务**调用** `IBookmarkService` 的公开入口
 * （`getOrCreateCanonical` / `parseAndSave` / `findListByUrl` / `findRootPageByHost`），
 * 反过来则没有依赖。想让后台复用一段解析逻辑时，必须先把它提升成公开契约，
 * 而不是顺手摸一个私有方法。
 *
 * 命名保留 "Bookmark" 而非 "Page"：这些是**面向用户可见功能**的后台操作，
 * 与 `IBookmarkService` / `BookmarkController` 遵循同一条既有约定（见根 `CLAUDE.md`）。
 */
interface IBookmarkAdminService {

    /** 管理员查询全部书签 */
    fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO>


    /** 管理员「重新获取」：重新解析网站标题与图标但不落库，暂存抓取结果供后续应用，返回预览数据 */
    fun adminRefetch(pageId: String): BookmarkRefetchVO

    /** 管理员「书签检测」：直接调用 scrapper 重新抓取一次，回传其给出的全部字段，并同步落库 isActivity/parseStatus */
    fun adminCheckLiveness(pageId: String): BookmarkLivenessVO

    /** 管理员应用「重新获取」的结果：按选择采用新标题/新图标并持久化（采用新图标会重抓高清 LOGO 到 OSS） */
    fun adminApplyRefetch(pageId: String, params: BookmarkRefetchApplyParams): BookmarkAdminVO

    /** 管理员「一键更新」：重新抓取网站信息并直接覆盖持久化标题/简介/图标/高清 LOGO，同步落库 isActivity/parseStatus */
    fun adminRefresh(pageId: String): BookmarkAdminVO

    /**
     * 管理员「图片资产 · 重新抓取」：只重抓图片，不覆盖标题/简介、不解锁人工锁、不改动书签活性。
     *
     * 本次没抓到图时保留库中现有图片（由 SiteAssetWriter 保证），所以"抓不到"不会把已有图片清空。
     */
    fun adminRefetchAssets(pageId: String): BookmarkAssetRefetchVO

    /**
     * 「网站管理」页任意 URL 活性检测在抓取成功后的落库同步：仅当该 URL 已对应某条 canonical 书签(按
     * urlHost+urlPath 匹配)时才生效，覆盖持久化标题/简介/图标/高清 LOGO，同步落库 isActivity/parseStatus；
     * 未命中已有书签时不新建记录，也不落库。返回是否实际同步了某条书签。
     */
    fun adminSyncFromExternalScrape(url: String, vo: ScrapeResponse): Boolean

    /** 管理员手动编辑书签基础信息（标题/简介），非空字段才会覆盖 */
    fun adminUpdateBasicInfo(pageId: String, params: BookmarkBasicInfoUpdateParams): BookmarkAdminVO

    /** 管理员手动设置某书签的分类（覆盖式），返回更新后的分类列表 */
    fun adminUpdateCategories(pageId: String, categoryIds: List<String>): List<CategoryVO>

    /** 管理员对某书签重新跑一次 DeepSeek 自动归类，返回更新后的分类列表 */
    fun adminRecategorize(pageId: String): List<CategoryVO>

    /** 管理员：AI 推荐与该书签相似的网站（仅展示，回填 exists 标记本地是否已收录） */
    fun adminSimilarSites(pageId: String): List<SimilarSite>

    /** 管理员「一键收录」：异步顺序收录相似网站域名，逐站通过 WebSocket 回推进度（抓取失败=幻觉，删除并跳过） */
    fun adminIngestSimilar(adminUid: String, domains: List<String>)

    fun adminGenerateAppName(pageId: String): String?

    /* 系统书签集 · AI 批量发布流程，见根 CLAUDE.md「Site Assets & the Scrapper Contract」以外的
     * 独立小节：这条流程不涉及 site_asset/AssetRolePolicy，是纯粹的「一批 URL → 一个展示集合」。 */

    /**
     * 步骤1：AI 从长文本中提取原始链接。仅供预览，不落库、不触发抓取。
     */
    fun adminExtractCollectionLinks(text: String): List<String>

    /**
     * 步骤2：异步顺序抓取一批链接（各自走 [getOrCreateCanonical][top.tcyeee.bookmarkify.server.IBookmarkService.getOrCreateCanonical]
     * + 解析），逐条通过 WebSocket([top.tcyeee.bookmarkify.config.websocket.SocketMsgType.SYSTEM_COLLECTION_CRAWL_UPDATE])
     * 推送给发起的管理员。顺序处理原因与 [adminIngestSimilar] 相同：批量不大，避免并发打爆 scrapper。
     */
    fun adminCrawlCollectionLinks(adminUid: String, jobId: String, urls: List<String>)

    /**
     * 步骤3：AI 根据已抓取成功的书签生成集合标题/描述建议，仅供预览，不落库。
     */
    fun adminGenerateCollectionMeta(bookmarks: List<CollectionBookmark>): CollectionMetaResult

    /**
     * 步骤3 确认后：发布为正式的系统书签集。
     */
    fun adminPublishSystemCollection(adminUid: String, params: PublishSystemCollectionParams): SystemCollectionVO

    /** 管理员查询全部系统书签集 */
    fun adminListSystemCollections(): List<SystemCollectionVO>

    /** 管理员修改一条系统书签集（标题/描述/包含的书签，整体覆盖） */
    fun adminUpdateSystemCollection(collectionId: String, params: PublishSystemCollectionParams): SystemCollectionVO
}
