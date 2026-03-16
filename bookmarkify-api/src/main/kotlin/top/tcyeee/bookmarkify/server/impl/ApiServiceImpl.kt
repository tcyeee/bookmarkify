package top.tcyeee.bookmarkify.server.impl

import cn.hutool.http.HttpUtil
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.IframelyConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.IframelyResponse
import top.tcyeee.bookmarkify.server.IApiService

@Service
class ApiServiceImpl(
    private val iframelyConfig: IframelyConfig,
    private val objectMapper: ObjectMapper,
) : IApiService {

    override fun queryWebsiteInfo(domain: String): IframelyResponse {
        val url = buildUrl(domain)
        val responseBody = runCatching {
            HttpUtil.createGet("https://iframe.ly/api/iframely")
                .form("url", url)
                .form("api_key", iframelyConfig.apiKey)
                .timeout(10000)
                .execute()
                .body()
        }.getOrElse { throw CommonException(ErrorType.E304, it.message ?: it.toString()) }

        val response = runCatching { objectMapper.readValue<IframelyResponse>(responseBody) }
            .getOrElse { throw CommonException(ErrorType.E304, "iframely 响应解析失败") }

        if (response.error != null) throw CommonException(ErrorType.E304, response.error)

        return response
    }

    private fun buildUrl(domain: String): String {
        if (domain.matches(Regex("^https?://.*"))) return domain
        return "https://$domain"
    }
}
