package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.dto.IframelyResponse

/**
 * @author tcyeee
 * @date 3/14/26 14:07
 */
interface IApiService {
    fun queryWebsiteInfo(domain: String): IframelyResponse

    /**
     * 通过 DeepSeek 从网站标题中提取品牌简称
     * @param title 网站标题，如"小红书 - 你的生活兴趣社区"
     * @return 提取到的简称（如"小红书"），无法判断时返回 null
     */
    fun inferAppName(title: String): String?
}