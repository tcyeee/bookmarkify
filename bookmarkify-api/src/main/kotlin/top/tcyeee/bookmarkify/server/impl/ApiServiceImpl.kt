package top.tcyeee.bookmarkify.server.impl

import cn.hutool.http.HttpUtil
import cn.hutool.json.JSONUtil
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.IframelyConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.WebsiteInfoVO
import top.tcyeee.bookmarkify.server.IApiService

@Service
class ApiServiceImpl(private val iframelyConfig: IframelyConfig) : IApiService {

    override fun queryWebsiteInfo(domain: String): WebsiteInfoVO {
        val url = buildUrl(domain)
        val responseBody = runCatching {
            HttpUtil.createGet("https://iframe.ly/api/iframely")
                .form("url", url)
                .form("api_key", iframelyConfig.apiKey)
                .timeout(10000)
                .execute()
                .body()
        }.getOrElse { throw CommonException(ErrorType.E304, it.message ?: it.toString()) }

        val json = runCatching { JSONUtil.parseObj(responseBody) }
            .getOrElse { throw CommonException(ErrorType.E304, "iframely 响应解析失败") }

        if (json.containsKey("error")) {
            throw CommonException(ErrorType.E304, json.getStr("error") ?: "iframely 请求失败")
        }

        val meta = json.getJSONObject("meta")
        val links = json.getJSONObject("links")

        val thumbnailUrl = links?.getJSONArray("thumbnail")
            ?.getJSONObject(0)?.getStr("href")

        val iconUrl = links?.getJSONArray("icon")
            ?.getJSONObject(0)?.getStr("href")

        return WebsiteInfoVO(
            title = meta?.getStr("title"),
            description = meta?.getStr("description"),
            siteName = meta?.getStr("site"),
            canonicalUrl = meta?.getStr("canonical") ?: json.getStr("url"),
            thumbnailUrl = thumbnailUrl,
            iconUrl = iconUrl,
            author = meta?.getStr("author"),
            medium = meta?.getStr("medium"),
        )
    }

    private fun buildUrl(domain: String): String {
        if (domain.matches(Regex("^https?://.*"))) return domain
        return "https://$domain"
    }
}
