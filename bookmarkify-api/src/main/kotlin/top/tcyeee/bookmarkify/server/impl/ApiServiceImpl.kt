package top.tcyeee.bookmarkify.server.impl

import cn.hutool.http.HttpUtil
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.DeepSeekConfig
import top.tcyeee.bookmarkify.config.entity.IframelyConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.DeepSeekMessage
import top.tcyeee.bookmarkify.entity.dto.DeepSeekRequest
import top.tcyeee.bookmarkify.entity.dto.DeepSeekResponse
import top.tcyeee.bookmarkify.entity.dto.IframelyResponse
import top.tcyeee.bookmarkify.server.IApiService

@Service
class ApiServiceImpl(
    private val iframelyConfig: IframelyConfig,
    private val deepSeekConfig: DeepSeekConfig,
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

    override fun inferAppName(title: String): String? {
        if (title.isBlank()) return null

        val request = DeepSeekRequest(
            messages = listOf(
                DeepSeekMessage(
                    role = "system",
                    content = """
                        你是一个网站简称提取助手。根据用户提供的网站标题，提取最简洁的品牌名或产品名。
                        规则：只返回简称本身，不要任何解释、标点或额外文字；无法判断时返回空字符串。
                        示例：
                        - "小红书 - 你的生活兴趣社区" → 小红书
                        - "Bilibili - 弹幕视频网" → Bilibili
                        - "GitHub: Let's build from here" → GitHub
                    """.trimIndent()
                ),
                DeepSeekMessage(role = "user", content = title),
            )
        )

        val responseBody = runCatching {
            HttpUtil.createPost("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer ${deepSeekConfig.apiKey}")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(request))
                .timeout(10000)
                .execute()
                .body()
        }.getOrNull() ?: return null

        return runCatching {
            objectMapper.readValue<DeepSeekResponse>(responseBody)
                .choices?.firstOrNull()?.message?.content
                ?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun buildUrl(domain: String): String {
        if (domain.matches(Regex("^https?://.*"))) return domain
        return "https://$domain"
    }
}
