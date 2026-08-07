package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
import top.tcyeee.bookmarkify.entity.BookmarkBasicInfoUpdateParams
import top.tcyeee.bookmarkify.entity.BookmarkIconUpdateParams
import top.tcyeee.bookmarkify.entity.BookmarkSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.PageEntity

/**
 * @author tcyeee
 * @date 3/10/24 15:45
 */
import com.baomidou.mybatisplus.core.metadata.IPage
import top.tcyeee.bookmarkify.entity.BookmarkAdminVO
import top.tcyeee.bookmarkify.entity.BookmarkAssetRefetchVO
import top.tcyeee.bookmarkify.entity.BookmarkLivenessVO
import top.tcyeee.bookmarkify.entity.BookmarkRefetchApplyParams
import top.tcyeee.bookmarkify.entity.BookmarkRefetchVO
import top.tcyeee.bookmarkify.entity.BookmarkSearchVO
import top.tcyeee.bookmarkify.entity.CategoryVO
import top.tcyeee.bookmarkify.entity.BookmarkImportPreviewVO
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.SimilarSite

interface IBookmarkService : IService<PageEntity> {
    /** 每 5 分钟对账一次 PENDING 书签：只负责「异步解析事件丢失/未完成」的兜底重投，不做活性判断 */
    fun checkAll()

    /**
     * 把用户桌面上还停在 BOOKMARK_LOADING 的书签补投递给解析线程池。
     *
     * 既是批量导入的正式消费通道（导入只落库、不投递事件，否则会把解析线程池连同 HTTP 线程一起打满），
     * 也兜底 addOne 丢失的解析事件。按线程池空闲队列容量决定投递量，跑在调度线程上。
     */
    fun drainStuckLoading()

    /** 定时扫描 UNREACHABLE 书签（含已认证）：ping 通后重新触发解析，结果写入 bookmark_ping_log；异步执行，不占用调度线程 */
    fun retryUnreachableBookmarks()

    /**
     * 抓取成功后的元数据富化（分类打标 + NSFW 判定，均为 DeepSeek 调用）。
     *
     * 由 BookmarkEnrichEvent 在**独立线程池**上触发，不占用解析池——这两件事用户看不到，
     * 不该让它们的耗时体现在「加书签要转多久圈」上。
     */
    fun enrich(pageId: String)

    /**
     * 抓取成功后补一张页面截图，作为详情面板的封面。
     *
     * 由 BookmarkScreenshotEvent 在**单线程**的截图池上触发。单线程是硬约束：截图强制走
     * 无头浏览器，而 scrapper 侧的 Chrome 由一把全局互斥锁串行化，这边多开只会一起堵在
     * 对端那把锁上。抓不到就没有封面，前端按可选处理。
     */
    fun captureScreenshot(pageId: String)

    /**
     * 书签详情弹窗顶部那张封面的签名地址（截图优先，退 og:image）。
     *
     * **按需单取，不随桌面列表下发**：桌面可能有几百条书签，而封面只在用户点开某一条时才看得到，
     * 给每条都签一个几百字节的 URL 是纯粹的浪费。
     *
     * @param linkId `bookmark_user_link.id`，同时充当归属校验的凭据——查不到该 uid 名下的这条链接
     *   就返回 null，不会泄露别人书签的封面
     * @return 没有可用封面时返回 null，前端据此**不渲染任何占位**
     */
    fun coverOf(linkId: String, uid: String): String?

    /** 定时扫描 SUCCESS 书签（含已认证）做活性复查，结果写入 bookmark_ping_log；异步执行，不占用调度线程 */
    fun livenessCheckStaleBookmarks()

    /** 添加书签并异步检查 */
    fun addOne(url: String, uid: String): UserLayoutNodeVO

    /** 为新用户设置默认书签 */
    fun setDefaultBookmark(uid: String)

    /** 为新用户设置默认功能 */
    fun setDefaultFunction(uid: String)

    /**
     * 搜索书签（用户端「添加」搜索，返回含小图标的精简结果）。
     *
     * 匹配范围是 site 层（域名/品牌名/短名），不下探到具体页面标题；同时排除 NSFW 站点。
     */
    fun search(name: String): List<BookmarkSearchVO>

    /** 关联一个已验证通过的书签 */
    fun linkOne(pageId: String, uid: String): UserLayoutNodeVO

    /** 查看我的全部书签 */
    fun allOfMyBookmark(uid: String, params: AllOfMyBookmarkParams): IPage<BookmarkShow>

    /** 解析 Chrome 书签 HTML，返回含重复标记的预览列表（不写库） */
    fun previewImport(file: MultipartFile, uid: String): BookmarkImportPreviewVO

    /** 导入 Chrome 书签；skipUrls 为用户选择跳过的完整 URL 集合；返回创建好的 LOADING 占位节点列表 */
    fun importBookmarkFile(file: MultipartFile, uid: String, skipUrls: Set<String> = emptySet()): List<UserLayoutNodeVO>

    /** 管理员查询全部书签 */
    fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO>

    /** 管理员修改书签图标设置（图片内边距 iconPadding、图标背景色 iconBgColor） */
    fun adminUpdateIcon(pageId: String, params: BookmarkIconUpdateParams)

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

    /** 解析书签,然后保存到数据库,同时通过 WebSocket 通知用户（异步事件入口） */
    fun parseAndNotice(uid: String, pageId: String, userLinkId: String, nodeId: String)

    /**
     * 通过网址解析为书签,同时重新绑定到添加这个网址的用户（异步事件入口）
     * 1.解析书签,更新书签状态(之前是LOADING)
     * 2.根据host重新绑定用户自定义书签
     * 3.修改用户布局元素状态(之前是LOADING)
     *
     * 为什么要重新绑定？
     * 答: 用户添加网址的时候是批量添加的,只能提前批量返回用户自定义的书签,用户自定义的书签具体有没有存在源书签还不知道,所以查询完毕知道以后,再重新关联回去
     */
    fun parseAndResetUserItem(uid: String, rawUrl: String, userLinkId: String, layoutNodeId: String)

    /** 根据书签ID解析书签并保存：依据配置决定使用远程 scrapper 还是内置解析器（异步事件入口） */
    fun parseAndSave(pageId: String)

    /** 通过 scrapper 远程解析书签元信息并持久化；若书签已通过验证则直接返回已有记录 */
    fun parseBookmarkByApi(bookmark: PageEntity): PageEntity

    /**
     * 批量按 host 域名查询书签列表（忽略路径，一个 host 现在可能对应多条不同路径的 canonical 记录）。
     * 用于「该域名下是否已收录过任意页面」这类域名级别的存在性判断（如相似站点推荐去重）。
     */
    fun findListByHost(defaultBookmarkify: List<String>): List<PageEntity>

    /**
     * 按 host 查该域名的**首页**记录，不存在时返回 null。
     *
     * 刻意不是「匹配任意一条」：域名下已收录过某个深链（如某个 YouTube 视频），并不代表首页也收录了，
     * 这两件事对收录判断的结论完全不同。旧实现是 `eq(urlHost).one()`，在同一 host 有多条路径时
     * 会直接抛异常。
     */
    fun findRootPageByHost(host: String): PageEntity?

    /**
     * 按网址获取或创建 canonical 页面记录（顺带保证 `site` 行存在），不触发抓取。
     * 需要抓取的调用方自己判断 [PageEntity.parseStatus] 后发事件。
     */
    fun getOrCreateCanonical(url: String): PageEntity

    /**
     * 管理员「一键分类」批量检查全部 canonical 书签是否 NSFW(涉黄/涉赌等违规内容)，命中的直接回写 nsfw=true。
     * 只会把 false 改成 true，不会清除已有的 nsfw 标记（避免 LLM 判断不稳定导致反复摇摆）。
     * @return (本次扫描的书签总数, 新命中 NSFW 的书签数)
     */
    fun checkNsfwForAll(): Pair<Int, Int>

    /** 批量按完整 URL（host+path）精确匹配书签列表，用于为新用户初始化默认书签 */
    fun findListByUrl(urls: List<String>): List<PageEntity>
}
