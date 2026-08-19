package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.dto.AiReviewOutcome
import top.tcyeee.bookmarkify.entity.dto.CategoryCandidate
import top.tcyeee.bookmarkify.entity.dto.CollectionBookmark
import top.tcyeee.bookmarkify.entity.dto.CollectionMetaResult
import top.tcyeee.bookmarkify.entity.dto.NsfwCheckResult
import top.tcyeee.bookmarkify.entity.dto.ProposedCategory
import top.tcyeee.bookmarkify.entity.dto.scrape.CacheMode
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeRequest
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.SimilarSite
import top.tcyeee.bookmarkify.entity.enums.PingOutcome

/**
 * @author tcyeee
 * @date 3/14/26 14:07
 */
interface IApiService {
    /** 用业务默认参数解析网站基础信息（等价于 `scrape(domain, scrapeRequest(url))`） */
    fun queryWebsiteInfo(domain: String): ScrapeResponse

    /**
     * 业务链路统一的抓取参数。
     *
     * **所有会落库的抓取都必须经由这里拼请求**，不要直接 `ScrapeRequest(url = ...)` ——
     * 契约默认值是给通用调用方定的（`assets.download = PROBE`，即下载完就丢），
     * 书签鸭的策略是"抓到的图一律进我方 OSS"，两者并不一致。曾经每个调用点各自 new
     * 一个 ScrapeRequest，于是全线静默退化成 PROBE，库里一个 storageKey 都没有。
     *
     * 唯一的例外是后台抓取调试页：它的全部参数都由使用者在界面上指定，本就不该被业务
     * 默认值覆盖，也不该往 OSS 里写测试数据。
     *
     * @param cacheMode 后台"重试/重新获取"必须传 [CacheMode.BYPASS]，否则可能直接命中
     *   scrapper 侧缓存，等于没重试
     * @param screenshot 是否要页面截图。默认取 `ScrapperConfig.screenshot`（生产为关）。
     *   显式传 `true` 的只有截图补抓任务——截图强制走无头浏览器，而 scrapper 侧的
     *   Chrome 是全局串行的，放进主解析链路会让每条书签排队等浏览器。
     * @param extractAssets 是否让 scrapper 提取页面声明的图片。**只有截图补抓任务传 false**：
     *   那些图在主抓取里已经落过库了，再探测一轮只是白跑几十次 HTTP。除此之外一律 true ——
     *   它一旦为 false，这次抓取就一张图都不会带回来，新书签永远拿不到图标。
     */
    fun scrapeRequest(
        url: String,
        cacheMode: CacheMode = CacheMode.DEFAULT,
        screenshot: Boolean? = null,
        extractAssets: Boolean = true,
    ): ScrapeRequest

    /** 完全控制 scrapper 行为的抓取入口；参数请优先用 [scrapeRequest] 生成。 */
    fun scrape(domain: String, request: ScrapeRequest): ScrapeResponse

    /**
     * 通过 DeepSeek 从网站标题中提取品牌简称
     * @param title 网站标题，如"小红书 - 你的生活兴趣社区"
     * @return 提取到的简称（如"小红书"），无法判断时返回 null
     */
    fun inferAppName(title: String): String?

    /**
     * 通过 DeepSeek 从固定候选词表中为网站挑选分类 slug（可多个）。
     * @return 命中的 slug 列表（已按 candidates 校验、去重）；失败或无结果返回空列表。
     */
    fun inferCategories(
        title: String?,
        description: String?,
        host: String,
        candidates: List<CategoryCandidate>,
    ): List<String>

    /**
     * 通过 DeepSeek 为网站提议分类，**允许提出词表里没有的新分类**。
     *
     * 与 [inferCategories] 是两条刻意分开的路径：那条是闭词表的，模型只能从 [candidates] 里挑，
     * 词表为空时直接返回空 —— 自动抓取链路必须用它，否则每收录一个站点都可能往字典里塞个新词，
     * 分类体系会被爬虫写崩。这条是开词表的，只给管理员在后台显式点击时用。
     *
     * @return 提议的分类（slug 已归一化为 `[a-z0-9-]`，含既有词与新词）；失败或无结果返回空列表。
     */
    fun proposeCategories(
        title: String?,
        description: String?,
        host: String,
        existing: List<CategoryCandidate>,
    ): List<ProposedCategory>

    /**
     * 通过 DeepSeek（纯知识，不联网）推荐若干功能/定位相似的网站。
     * @return 相似网站列表；失败或无结果返回空列表。
     */
    fun inferSimilarSites(title: String?, description: String?, host: String): List<SimilarSite>

    /**
     * 通过 DeepSeek 判断网站是否涉及成人色情、赌博博彩等违规/不宜内容(NSFW)，命中时附带简短理由。
     * 保守策略：调用失败或无法判断时返回 [NsfwCheckResult.nsfw]=false，不误伤正常网站。
     */
    fun inferNsfw(title: String?, description: String?, host: String): NsfwCheckResult

    /**
     * 通过 DeepSeek 判断分享内容是否涉及色情、涉政、歧视侮辱等不合规信息（用于分享发布审核）。
     * 与 [inferNsfw] 的 fail-open 策略不同：调用失败/解析失败会返回 [AiReviewOutcome.Unavailable]，
     * 由调用方按 fail-closed 策略处理，不能与"内容正常"混淆。
     */
    fun inferContentViolation(title: String?, description: String?, host: String): AiReviewOutcome

    /**
     * 通过 scrapper /ping 探测目标网站是否存活（走代理）。
     *
     * 返回三态而不是 Boolean：scrapper 不可达、鉴权失败、并发超限被限流，这些都属于
     * 「我方没探到」而非「站点死了」，一律返回 [PingOutcome.UNKNOWN]，调用方不得据此改动书签状态。
     * 详见 [PingOutcome]。
     */
    fun pingWebsite(url: String): PingOutcome

    /**
     * 通过 DeepSeek 从任意长文本（文章、聊天记录、Markdown…）中提取其中出现的原始链接。
     * 用于系统书签集发布流程 · 步骤1。返回的链接已去重、已补全 `https://` 前缀；
     * 可能包含模型误判的非链接文本，由后续抓取阶段（无法解析目标）自然过滤，调用方无需校验。
     * 失败或未识别到任何链接时返回空列表。
     */
    fun extractLinks(text: String): List<String>

    /**
     * 通过 DeepSeek 根据一批已抓取的书签生成集合标题与描述建议，用于系统书签集发布流程 · 步骤3。
     * 调用失败时返回按书签标题拼接的兜底文案，而不是抛异常——这里只是给管理员的初始建议，
     * 发布前本就可编辑，没必要因为 AI 不可用而中断整个发布流程。
     */
    fun generateCollectionMeta(bookmarks: List<CollectionBookmark>): CollectionMetaResult
}