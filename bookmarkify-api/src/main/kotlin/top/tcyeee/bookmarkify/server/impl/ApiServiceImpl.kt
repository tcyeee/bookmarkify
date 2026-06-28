package top.tcyeee.bookmarkify.server.impl

import cn.hutool.http.HttpUtil
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.DeepSeekConfig
import top.tcyeee.bookmarkify.config.entity.ScrapperConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.CategoryCandidate
import top.tcyeee.bookmarkify.entity.dto.DeepSeekMessage
import top.tcyeee.bookmarkify.entity.dto.DeepSeekRequest
import top.tcyeee.bookmarkify.entity.dto.DeepSeekResponse
import top.tcyeee.bookmarkify.entity.dto.PingRequest
import top.tcyeee.bookmarkify.entity.dto.PingResponse
import top.tcyeee.bookmarkify.entity.dto.ScrapeRequest
import top.tcyeee.bookmarkify.entity.dto.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.SimilarSite
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

    override fun inferCategories(
        title: String?,
        description: String?,
        host: String,
        candidates: List<CategoryCandidate>,
    ): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val allowed = candidates.map { it.slug.lowercase() }.toSet()

        val catalogue = candidates.joinToString("\n") { c ->
            "- ${c.slug}（${c.name}）${c.description?.let { "：$it" } ?: ""}"
        }
        val systemPrompt = """
            你是一个网站分类助手。下面是允许使用的分类列表（slug 及含义）：
            $catalogue
            根据用户给出的网站信息，从上面的列表中选出 1~3 个最贴切的分类。
            规则：只返回 slug 本身，多个用英文逗号分隔；只能用列表里出现过的 slug；
            不要任何解释、标点或额外文字。实在无法判断时返回 other。
        """.trimIndent()
        val userContent = "host: $host\ntitle: ${title ?: ""}\ndescription: ${description ?: ""}"

        val request = DeepSeekRequest(
            messages = listOf(
                DeepSeekMessage(role = "system", content = systemPrompt),
                DeepSeekMessage(role = "user", content = userContent),
            ),
            maxTokens = 40,
        )

        val responseBody = runCatching {
            HttpUtil.createPost("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer ${deepSeekConfig.apiKey}")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(request))
                .timeout(10000)
                .execute()
                .body()
        }.getOrNull() ?: return emptyList()

        val raw = runCatching {
            objectMapper.readValue<DeepSeekResponse>(responseBody)
                .choices?.firstOrNull()?.message?.content
        }.getOrNull() ?: return emptyList()

        return raw.split(',', '，', '\n', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it in allowed }
            .distinct()
    }

    override fun inferSimilarSites(title: String?, description: String?, host: String): List<SimilarSite> {
        val systemPrompt = """
            你是一个网站推荐助手。根据用户给出的网站信息，推荐 8~10 个功能或定位相似的其它网站。
            严格只返回 JSON 数组，每个元素形如 {"name":"网站名","domain":"example.com","reason":"一句话理由"}。
            不要 markdown 代码块，不要任何额外解释文字。domain 只填主域名，不带 http 前缀。
        """.trimIndent()
        val userContent = "host: $host\ntitle: ${title ?: ""}\ndescription: ${description ?: ""}"
        val request = DeepSeekRequest(
            messages = listOf(
                DeepSeekMessage(role = "system", content = systemPrompt),
                DeepSeekMessage(role = "user", content = userContent),
            ),
            maxTokens = 800,
        )
        val responseBody = runCatching {
            HttpUtil.createPost("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer ${deepSeekConfig.apiKey}")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(request))
                .timeout(20000)
                .execute()
                .body()
        }.getOrNull() ?: return emptyList()
        val content = runCatching {
            objectMapper.readValue<DeepSeekResponse>(responseBody)
                .choices?.firstOrNull()?.message?.content
        }.getOrNull() ?: return emptyList()
        // 最多返回 10 个，防止模型偶发超量
        return parseSimilarSites(content).take(10)
    }

    /** 解析 DeepSeek 返回的文本为相似网站列表：剥离 ```json 围栏后按 JSON 数组解析，失败返回空。 */
    internal fun parseSimilarSites(content: String): List<SimilarSite> {
        val json = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { objectMapper.readValue<List<SimilarSite>>(json) }.getOrElse { emptyList() }
    }

    override fun pingWebsite(url: String): Boolean {
        val targetUrl = buildUrl(url)
        return runCatching {
            val request = PingRequest(url = targetUrl)
            val httpResponse = HttpUtil.createPost("${scrapperConfig.baseUrl.trimEnd('/')}/ping")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(request))
                .timeout(15000)
                .execute()
            if (!httpResponse.isOk) return false
            objectMapper.readValue<PingResponse>(httpResponse.body()).alive
        }.getOrElse { false }
    }

    private fun buildUrl(domain: String): String {
        if (domain.matches(Regex("^https?://.*"))) return domain
        return "https://$domain"
    }
}
