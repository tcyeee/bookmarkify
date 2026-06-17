package top.tcyeee.bookmarkify.server.impl

import cn.hutool.http.HttpUtil
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.DeepSeekConfig
import top.tcyeee.bookmarkify.config.entity.ScrapperConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.DeepSeekMessage
import top.tcyeee.bookmarkify.entity.dto.DeepSeekRequest
import top.tcyeee.bookmarkify.entity.dto.DeepSeekResponse
import top.tcyeee.bookmarkify.entity.dto.ScrapeRequest
import top.tcyeee.bookmarkify.entity.dto.ScrapeResponse
import top.tcyeee.bookmarkify.server.IApiService

@Service
class ApiServiceImpl(
    private val scrapperConfig: ScrapperConfig,
    private val deepSeekConfig: DeepSeekConfig,
    private val objectMapper: ObjectMapper,
) : IApiService {

    override fun queryWebsiteInfo(domain: String): ScrapeResponse {
        val url = buildUrl(domain)
        val request = ScrapeRequest(url = url)

        // scrapper 可能回退到无头浏览器（HEADLESS_TIMEOUT + IDLE_WAIT），超时给足 60s
        val httpResponse = runCatching {
            HttpUtil.createPost("${scrapperConfig.baseUrl.trimEnd('/')}/scrape")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(request))
                .timeout(60000)
                .execute()
        }.getOrElse { throw CommonException(ErrorType.E304, it.message ?: it.toString()) }

        val body = httpResponse.body()
        if (!httpResponse.isOk) {
            // 错误响应体形如 {"error":"timeout","detail":"..."}
            val msg = runCatching { objectMapper.readTree(body).path("error").asText(null) }.getOrNull()
                ?: "scrapper 返回 ${httpResponse.status}"
            throw CommonException(ErrorType.E304, msg)
        }

        return runCatching { objectMapper.readValue<ScrapeResponse>(body) }
            .getOrElse { throw CommonException(ErrorType.E304, "scrapper 响应解析失败") }
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
