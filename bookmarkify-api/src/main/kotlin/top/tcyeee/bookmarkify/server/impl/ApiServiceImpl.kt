package top.tcyeee.bookmarkify.server.impl

import cn.hutool.http.HttpRequest
import cn.hutool.http.HttpUtil
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.entity.DeepSeekConfig
import top.tcyeee.bookmarkify.config.entity.ScrapperConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.dto.AiReviewOutcome
import top.tcyeee.bookmarkify.entity.dto.CategoryCandidate
import top.tcyeee.bookmarkify.entity.dto.DeepSeekMessage
import top.tcyeee.bookmarkify.entity.dto.DeepSeekRequest
import top.tcyeee.bookmarkify.entity.dto.DeepSeekResponse
import top.tcyeee.bookmarkify.entity.dto.NsfwCheckResult
import top.tcyeee.bookmarkify.entity.dto.PingRequest
import top.tcyeee.bookmarkify.entity.dto.PingResponse
import top.tcyeee.bookmarkify.entity.dto.scrape.AssetDownload
import top.tcyeee.bookmarkify.entity.dto.scrape.AssetOptions
import top.tcyeee.bookmarkify.entity.dto.scrape.CacheMode
import top.tcyeee.bookmarkify.entity.dto.scrape.CacheOptions
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeRequest
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.scrape.ScreenshotOptions
import top.tcyeee.bookmarkify.entity.dto.SimilarSite
import top.tcyeee.bookmarkify.entity.entity.ScrapperCallLogEntity
import top.tcyeee.bookmarkify.mapper.ScrapperCallLogMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.utils.WebsiteParser

@Service
class ApiServiceImpl(
    private val scrapperConfig: ScrapperConfig,
    private val deepSeekConfig: DeepSeekConfig,
    private val objectMapper: ObjectMapper,
    private val scrapperCallLogMapper: ScrapperCallLogMapper,
) : IApiService {

    /** scrapper 侧 `SCRAPER_AUTH_TOKEN` 未配置时 `scrapperConfig.authToken` 留空，不发送该 header。 */
    private fun HttpRequest.withScrapperAuth(): HttpRequest =
        if (scrapperConfig.authToken.isBlank()) this
        else header("Authorization", "Bearer ${scrapperConfig.authToken}")

    /** 本地开发默认连 127.0.0.1 的 scrapper，多半是根本没起——直接把启动命令带进报错里，省得去翻文档 */
    private val scrapperStartupHint: String
        get() = if (scrapperConfig.baseUrl.contains("localhost") || scrapperConfig.baseUrl.contains("127.0.0.1"))
            "；本地开发需先启动: cd bookmarkify-scrapper && PORT=3001 cargo run -p scraper-service"
        else ""

    /**
     * scrapper 的错误码 → 我方错误类型。区分"目标站点的问题"与"我方抓取服务的问题"：
     * 前者说明书签确实抓不到（可据此判失联），后者只说明服务没起/配错/契约不同步。
     * 取值见 bookmarkify-scrapper `main.rs` 的 error_response 分支。
     */
    private fun classifyScrapperError(code: String?): ErrorType = when (code) {
        "INVALID_URL" -> ErrorType.E305                                          // URL 格式非法
        "FORBIDDEN_TARGET", "FETCH_FAILED", "HEADLESS_FAILED", "TIMEOUT" -> ErrorType.E304 // 目标站点打不开
        // UNAUTHORIZED(鉴权 token 配错)、OSS_FAILED、限流 503、422(请求体字段对不上)、
        // 以及任何未识别取值，都归为我方服务问题
        else -> ErrorType.E307
    }

    /**
     * 业务链路统一的抓取参数，见 [IApiService.scrapeRequest]。
     *
     * 关键的一条是 `download = UPLOAD`：契约默认的 PROBE 会把图片正文下载完直接丢弃，
     * 于是 `storageKey` 恒为空、`site_asset.storage_url` 恒为 NULL，前台只能回退到源站
     * 直连地址。图片落我方 OSS 是产品要求，不是可选优化。
     */
    override fun scrapeRequest(url: String, cacheMode: CacheMode): ScrapeRequest = ScrapeRequest(
        url = url,
        assets = AssetOptions(
            download = if (scrapperConfig.uploadAssets) AssetDownload.UPLOAD else AssetDownload.PROBE,
            maxBytes = scrapperConfig.assetMaxBytes,
            maxCount = scrapperConfig.assetMaxCount,
        ),
        // 开启会强制走无头浏览器，默认关；见 ScrapperConfig.screenshot
        screenshot = ScreenshotOptions(enabled = scrapperConfig.screenshot),
        cache = CacheOptions(mode = cacheMode),
    )

    override fun queryWebsiteInfo(domain: String): ScrapeResponse =
        scrape(domain, scrapeRequest(buildUrl(domain)))

    override fun scrape(domain: String, request: ScrapeRequest): ScrapeResponse {
        val url = request.url.takeIf { it.isNotBlank() } ?: buildUrl(domain)
        val endpoint = "${scrapperConfig.baseUrl.trimEnd('/')}/scrape"
        val startedAt = System.currentTimeMillis()

        // scrapper 可能回退到无头浏览器（HEADLESS_TIMEOUT + IDLE_WAIT），超时给足 60s
        val httpResponse = runCatching {
            HttpUtil.createPost(endpoint)
                .header("Content-Type", "application/json")
                .withScrapperAuth()
                .body(objectMapper.writeValueAsString(request))
                .timeout(60000)
                .execute()
        }.getOrElse {
            // 这一层的传输异常必然发生在 API ↔ scrapper 之间——目标站点能否打开是由
            // scrapper 判定后以 HTTP 错误码回报的。以前这里一律记成 E304「域名无法访问」，
            // 本地没起 scrapper 时就会把"服务没开"误报成"这个网站挂了"
            val msg = "无法连接抓取服务 $endpoint :: ${it.message ?: it.toString()}$scrapperStartupHint"
            logScrapperCall(url, startedAt, success = false, httpStatus = null, errorMsg = msg)
            throw CommonException(ErrorType.E307, msg)
        }

        val body = httpResponse.body()
        if (!httpResponse.isOk) {
            // 错误响应体形如 {"error":"FETCH_FAILED","detail":"..."}；deny_unknown_fields
            // 命中时 axum 返回的是纯文本，解析不出 error 字段也不能炸
            val json = runCatching { objectMapper.readTree(body) }.getOrNull()
            val code = json?.path("error")?.asText(null)
            // 认不出 error 字段（axum 的 422 是纯文本）时，原样带上响应体，别只剩一个状态码
            val detail = json?.path("detail")?.asText(null)?.takeIf { it.isNotBlank() }
                ?: body?.takeIf { code == null && it.isNotBlank() }?.take(200)
            val errorType = classifyScrapperError(code)
            val msg = buildString {
                append(code ?: "scrapper 返回 HTTP ${httpResponse.status}")
                if (detail != null) append(" :: ").append(detail)
                // 服务是活着的（有 HTTP 响应），所以这里只给地址不给"先启动服务"的提示
                if (errorType == ErrorType.E307) append(" (scrapper: $endpoint)")
            }
            logScrapperCall(url, startedAt, success = false, httpStatus = httpResponse.status, errorMsg = msg)
            throw CommonException(errorType, msg)
        }

        val scrapeResponse = runCatching { objectMapper.readValue<ScrapeResponse>(body) }
            .getOrElse {
                // 契约对不上是我方两侧代码不同步，不是目标站点的问题
                val msg = "scrapper 响应解析失败(契约不匹配) :: ${it.message?.take(200)}"
                logScrapperCall(url, startedAt, success = false, httpStatus = httpResponse.status, errorMsg = msg)
                throw CommonException(ErrorType.E307, msg)
            }

        logScrapperCall(
            url, startedAt, success = true, httpStatus = httpResponse.status,
            // 调用日志沿用"命中来源"这一列，取标题的出处作为代表；契约里出处是逐字段的，
            // 单列存不下全部，完整信息在 scrape_snapshot 里
            source = scrapeResponse.meta?.sources?.get("title")?.extractor?.name,
            cached = scrapeResponse.fetch.fromCache,
        )
        return scrapeResponse
    }

    /** 记录一次对 bookmarkify-scrapper /scrape 的调用；日志写入失败不影响主流程。 */
    private fun logScrapperCall(
        url: String,
        startedAt: Long,
        success: Boolean,
        httpStatus: Int?,
        source: String? = null,
        cached: Boolean? = null,
        errorMsg: String? = null,
    ) {
        runCatching {
            scrapperCallLogMapper.insert(
                ScrapperCallLogEntity(
                    url = url,
                    urlHost = runCatching { WebsiteParser.urlWrapper(url).urlHost }.getOrDefault(url),
                    success = success,
                    httpStatus = httpStatus,
                    source = source,
                    cached = cached,
                    durationMs = System.currentTimeMillis() - startedAt,
                    errorMsg = errorMsg?.take(500),
                )
            )
        }.onFailure { log.warn("[logScrapperCall] 写入 scrapper 调用日志失败: ${it.message}") }
    }

    override fun inferAppName(title: String): String? {
        if (title.isBlank()) return null

        val request = DeepSeekRequest(
            messages = listOf(
                DeepSeekMessage(
                    role = "system",
                    content = """
                        你是一个网站简称提取助手。根据用户提供的网站标题，提取最简洁的品牌名或产品名。
                        规则：只返回简称本身，不要任何解释、标点或额外文字；无法判断时只返回 NONE 这一个词，不要输出"空字符串"等描述性文字。
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
                // 模型偶尔会把"无法判断时返回空字符串"的指令误当作要输出的内容，直接吐出"空字符串"这几个字
                // 而非真正的空响应，导致该字面量被当作合法简称存入 appName 并覆盖真实标题。这里除了空白，
                // 额外过滤掉约定的哨兵词 NONE 以及历史上曾被污染的字面量"空字符串"，双重兜底。
                ?.trim()?.takeIf { it.isNotBlank() && it != "NONE" && it != "空字符串" }
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
                .withScrapperAuth()
                .body(objectMapper.writeValueAsString(request))
                .timeout(15000)
                .execute()
            if (!httpResponse.isOk) return false
            objectMapper.readValue<PingResponse>(httpResponse.body()).alive
        }.getOrElse { false }
    }

    override fun inferNsfw(title: String?, description: String?, host: String): NsfwCheckResult {
        val systemPrompt = """
            你是一个网站内容安全审核助手。根据用户给出的网站信息，判断该网站是否可能涉及
            成人色情、赌博博彩、或其他明显违法违规内容。
            规则：如果不涉及，只返回 no；如果涉及，返回一个不超过15个字的简短理由（例如：疑似成人色情内容、
            疑似赌博博彩内容），不要任何解释、标点或额外文字；信息不足或无法判断时返回 no。
        """.trimIndent()
        val userContent = "host: $host\ntitle: ${title ?: ""}\ndescription: ${description ?: ""}"

        val request = DeepSeekRequest(
            messages = listOf(
                DeepSeekMessage(role = "system", content = systemPrompt),
                DeepSeekMessage(role = "user", content = userContent),
            ),
            maxTokens = 30,
        )

        val responseBody = runCatching {
            HttpUtil.createPost("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer ${deepSeekConfig.apiKey}")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(request))
                .timeout(10000)
                .execute()
                .body()
        }.getOrNull() ?: return NsfwCheckResult(false)

        val raw = runCatching {
            objectMapper.readValue<DeepSeekResponse>(responseBody)
                .choices?.firstOrNull()?.message?.content
        }.getOrNull()?.trim() ?: return NsfwCheckResult(false)

        return if (raw.isBlank() || raw.lowercase().startsWith("no")) {
            NsfwCheckResult(false)
        } else {
            NsfwCheckResult(true, raw)
        }
    }

    override fun inferContentViolation(title: String?, description: String?, host: String): AiReviewOutcome {
        val systemPrompt = """
            你是一个内容安全审核助手。根据用户给出的网站信息，判断其中是否包含
            色情低俗、涉政敏感、歧视侮辱等任意不合规内容。
            规则：如果内容正常，只返回 OK；如果不合规，返回一个不超过10个字的简短违规类别
            （例如：色情内容、涉政内容、歧视侮辱内容）。不要任何解释、标点或额外文字。
        """.trimIndent()
        val userContent = "host: $host\ntitle: ${title ?: ""}\ndescription: ${description ?: ""}"

        val request = DeepSeekRequest(
            messages = listOf(
                DeepSeekMessage(role = "system", content = systemPrompt),
                DeepSeekMessage(role = "user", content = userContent),
            ),
            maxTokens = 20,
        )

        val responseBody = runCatching {
            HttpUtil.createPost("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer ${deepSeekConfig.apiKey}")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(request))
                .timeout(10000)
                .execute()
                .body()
        }.getOrNull() ?: return AiReviewOutcome.Unavailable

        val raw = runCatching {
            objectMapper.readValue<DeepSeekResponse>(responseBody)
                .choices?.firstOrNull()?.message?.content
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return AiReviewOutcome.Unavailable

        return if (raw.equals("OK", ignoreCase = true)) AiReviewOutcome.Pass else AiReviewOutcome.Rejected(raw)
    }

    private fun buildUrl(domain: String): String {
        if (domain.matches(Regex("^https?://.*"))) return domain
        return "https://$domain"
    }
}
