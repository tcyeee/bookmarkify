package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.dto.AiReviewOutcome
import top.tcyeee.bookmarkify.entity.dto.CategoryCandidate
import top.tcyeee.bookmarkify.entity.dto.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.SimilarSite

/**
 * @author tcyeee
 * @date 3/14/26 14:07
 */
interface IApiService {
    /** 通过自部署的 bookmarkify-scrapper 解析网站基础信息 */
    fun queryWebsiteInfo(domain: String): ScrapeResponse

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
     * 通过 DeepSeek（纯知识，不联网）推荐若干功能/定位相似的网站。
     * @return 相似网站列表；失败或无结果返回空列表。
     */
    fun inferSimilarSites(title: String?, description: String?, host: String): List<SimilarSite>

    /**
     * 通过 DeepSeek 判断网站是否涉及成人色情、赌博博彩等违规/不宜内容(NSFW)。
     * 保守策略：调用失败或无法判断时返回 false，不误伤正常网站。
     */
    fun inferNsfw(title: String?, description: String?, host: String): Boolean

    /**
     * 通过 DeepSeek 判断分享内容是否涉及色情、涉政、歧视侮辱等不合规信息（用于分享发布审核）。
     * 与 [inferNsfw] 的 fail-open 策略不同：调用失败/解析失败会返回 [AiReviewOutcome.Unavailable]，
     * 由调用方按 fail-closed 策略处理，不能与"内容正常"混淆。
     */
    fun inferContentViolation(title: String?, description: String?, host: String): AiReviewOutcome

    /**
     * 通过 scrapper /ping 探测目标网站是否存活（走代理）。
     * 网络异常或 scrapper 不可达时返回 false（保守策略，不阻塞后续抓取兜底逻辑）。
     */
    fun pingWebsite(url: String): Boolean
}