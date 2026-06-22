package top.tcyeee.bookmarkify.server

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
}