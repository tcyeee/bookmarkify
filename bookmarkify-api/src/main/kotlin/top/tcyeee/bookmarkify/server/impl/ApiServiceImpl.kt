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
import top.tcyeee.bookmarkify.entity.dto.DeepSeekUsage
import top.tcyeee.bookmarkify.entity.dto.NsfwCheckResult
import top.tcyeee.bookmarkify.entity.dto.PingRequest
import top.tcyeee.bookmarkify.entity.dto.PingResponse
import top.tcyeee.bookmarkify.entity.dto.ProposedCategory
import top.tcyeee.bookmarkify.entity.dto.scrape.AssetDownload
import top.tcyeee.bookmarkify.entity.dto.scrape.AssetOptions
import top.tcyeee.bookmarkify.entity.dto.scrape.CacheMode
import top.tcyeee.bookmarkify.entity.dto.scrape.CacheOptions
import top.tcyeee.bookmarkify.entity.dto.scrape.ExtractOptions
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeRequest
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.scrape.ScreenshotOptions
import top.tcyeee.bookmarkify.entity.dto.SimilarSite
import top.tcyeee.bookmarkify.entity.entity.AiCallLogEntity
import top.tcyeee.bookmarkify.entity.entity.ScrapperCallLogEntity
import top.tcyeee.bookmarkify.entity.enums.AiCallScene
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import top.tcyeee.bookmarkify.server.liveness.LivenessPolicy
import top.tcyeee.bookmarkify.mapper.AiCallLogMapper
import top.tcyeee.bookmarkify.mapper.ScrapperCallLogMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.utils.ScrapeTargetGuard
import top.tcyeee.bookmarkify.utils.WebsiteParser

@Service
class ApiServiceImpl(
    private val scrapperConfig: ScrapperConfig,
    private val deepSeekConfig: DeepSeekConfig,
    private val objectMapper: ObjectMapper,
    private val scrapperCallLogMapper: ScrapperCallLogMapper,
    private val aiCallLogMapper: AiCallLogMapper,
) : IApiService {

    /** scrapper 侧 `SCRAPER_AUTH_TOKEN` 未配置时 `scrapperConfig.authToken` 留空，不发送该 header。 */
    private fun HttpRequest.withScrapperAuth(): HttpRequest =
        if (scrapperConfig.authToken.isBlank()) this
        else header("Authorization", "Bearer ${scrapperConfig.authToken}")

    /**
     * 分别设置连接超时与读取超时。
     *
     * hutool 的 `timeout(n)` 把两者设成同一个值，于是 scrape 那条为了等无头浏览器而给到 60s 的
     * **读取**超时，同时也成了 60s 的**连接**超时：抓取服务没起、端口不通时，一个解析线程要白白
     * 等满一分钟才能发现连不上——而「TCP 连不上」这件事本来几百毫秒就有结论。
     * 连接阶段该快速失败，慢的只应该是等对方干活的那一段。
     */
    private fun HttpRequest.timeouts(readMs: Int): HttpRequest =
        setConnectionTimeout(CONNECT_TIMEOUT_MS).setReadTimeout(readMs)

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
        "FETCH_FAILED", "HEADLESS_FAILED", "TIMEOUT" -> ErrorType.E304           // 目标站点打不开
        // 我方(scrapper 侧)拒绝去抓：内网地址，或目标不是域名而是裸 IP / localhost。
        // 从 E304 里拆出来的——"站点打不开"是关于站点的结论，"我们不去打开它"是我方的决定，
        // 混在一起时 ping 路径会把后者判成失联，等于用一个安全/业务决策给用户的书签判死。
        // 正常情况下这个分支根本不该被走到：同一条规则在发请求之前就由 ScrapeTargetGuard
        // 拦掉了，这里能命中说明两侧规则不一致(比如站点 302 到了一个 IP)，值得被看见
        "FORBIDDEN_TARGET" -> ErrorType.E308
        // 负缓存命中(60s TTL)：这条 URL 刚刚抓失败过。它是**关于目标站点的事实**——scrapper
        // 只在取回阶段失败时写负缓存，且刻意排除了 INVALID_URL / FORBIDDEN_TARGET，所以命中
        // 即等价于"刚判过一次抓不到"。归进 else 的后果相当重：会被 isScrapperUnavailable()
        // 认作我方故障，于是 parseByApi 刻意把书签留在 PENDING 不收口，用户桌面上那个节点要
        // 一直转到 30 分钟后 drainStuckLoading 的陈旧阈值到期。同一网址被两个用户先后添加、
        // 或者手快点了两次，第二次就会撞上。
        "RECENTLY_FAILED" -> ErrorType.E304
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
    override fun scrapeRequest(
        url: String,
        cacheMode: CacheMode,
        screenshot: Boolean?,
        extractAssets: Boolean,
    ): ScrapeRequest {
        val wantShot = screenshot ?: scrapperConfig.screenshot
        return ScrapeRequest(
            url = url,
            // 由调用方显式决定，**不能**从 wantShot 反推：截图开关有一个来自配置的兜底
            // (`ScrapperConfig.screenshot`)，一旦有人把它打开，主解析链路就会跟着停掉图片提取，
            // 于是全站新书签一张图标都抓不到，而日志里什么都看不出来。
            // 真正"不需要图"的只有截图补抓那一条路，它自己传 extractAssets = false。
            extract = ExtractOptions(assets = extractAssets),
            assets = AssetOptions(
                download = if (scrapperConfig.uploadAssets) AssetDownload.UPLOAD else AssetDownload.PROBE,
                maxBytes = scrapperConfig.assetMaxBytes,
                maxCount = scrapperConfig.assetMaxCount,
            ),
            // 开启会强制走无头浏览器，默认关；见 ScrapperConfig.screenshot
            screenshot = ScreenshotOptions(
                enabled = wantShot,
                fullPage = false,
                format = scrapperConfig.screenshotFormat,
                quality = scrapperConfig.screenshotQuality,
            ),
            cache = CacheOptions(mode = cacheMode),
        )
    }

    override fun queryWebsiteInfo(domain: String): ScrapeResponse =
        scrape(domain, scrapeRequest(buildUrl(domain)))

    override fun scrape(domain: String, request: ScrapeRequest): ScrapeResponse {
        val url = request.url.takeIf { it.isNotBlank() } ?: buildUrl(domain)

        // 抓取目标必须是域名。这道门放在这里而不是各个调用点：所有抓取——用户添加书签、
        // 后台重新获取/一键更新/重抓资产/活性检测、截图补抓、调试抓取——都经过本方法，
        // 而它们当中只有解析主链路自带链接类型过滤，其余六个入口一个都没有。
        // 拒绝发生在**发请求之前**，因此不写 scrapper_call_log：那张表记的是"我方调用过
        // scrapper"，本次根本没有调用，记一条失败反而会污染调用成功率。见 ScrapeTargetGuard
        ScrapeTargetGuard.assertScrapable(url)

        val endpoint = "${scrapperConfig.baseUrl.trimEnd('/')}/scrape"
        val startedAt = System.currentTimeMillis()

        // scrapper 可能回退到无头浏览器（HEADLESS_TIMEOUT + IDLE_WAIT），超时给足 60s
        val httpResponse = runCatching {
            HttpUtil.createPost(endpoint)
                .header("Content-Type", "application/json")
                .withScrapperAuth()
                .body(objectMapper.writeValueAsString(request))
                .timeouts(60000)
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
            // 请求发的是 render.mode=AUTO，到底走了 Layer 1 / Layer 2 / 站点 API 只有 scrapper 知道。
            // 这跟 source 是两件事：被反爬拦下后由站点 API 救回来的页面，source 仍可能是 html
            layerUsed = scrapeResponse.fetch.layerUsed.name,
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
        layerUsed: String? = null,
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
                    layerUsed = layerUsed,
                    durationMs = System.currentTimeMillis() - startedAt,
                    errorMsg = errorMsg?.take(500),
                )
            )
        }.onFailure { log.warn("[logScrapperCall] 写入 scrapper 调用日志失败: ${it.message}") }
    }

    /**
     * 所有 DeepSeek 调用的唯一出口：统一鉴权、超时、解析，并把每一次通讯落到 `ai_call_log`。
     *
     * 六个业务场景此前各自复制了一份「HttpUtil 发请求 + readValue + runCatching 吞掉异常」的样板，
     * 于是一次判定为什么得出这个结果在事后完全不可追溯：模型原样回了什么、花了多少 token、
     * 是超时还是被限流、还是干脆返回了空 choices，全都只存在于那一次调用的栈里。
     * 抓取结果最终会落到 site_asset 还能复查，AI 的输出却是即用即弃的。
     *
     * @param subject 本次判定的对象（域名或标题），只用于日志检索
     * @return 模型返回的正文（已 trim，空白视为无内容）；任何一步失败都返回 null，
     *   由各场景按自己的 fail-open / fail-closed 策略兜底
     */
    private fun chatCompletion(
        scene: AiCallScene,
        subject: String?,
        request: DeepSeekRequest,
        readTimeoutMs: Int = AI_READ_TIMEOUT_MS,
    ): String? {
        val startedAt = System.currentTimeMillis()
        val requestJson = runCatching { objectMapper.writeValueAsString(request) }.getOrElse {
            logAiCall(scene, subject, request.model, null, startedAt, success = false, errorMsg = "请求序列化失败: ${it.message}")
            return null
        }

        val httpResponse = runCatching {
            HttpUtil.createPost(DEEPSEEK_ENDPOINT)
                .header("Authorization", "Bearer ${deepSeekConfig.apiKey}")
                .header("Content-Type", "application/json")
                .body(requestJson)
                .timeouts(readTimeoutMs)
                .execute()
        }.getOrElse {
            logAiCall(
                scene, subject, request.model, requestJson, startedAt, success = false,
                errorMsg = "无法连接 DeepSeek $DEEPSEEK_ENDPOINT :: ${it.message ?: it.toString()}",
            )
            return null
        }

        val body = httpResponse.body()
        if (!httpResponse.isOk) {
            // 401(key 失效)、402(余额不足)、429(限流) 都长这样，光看状态码就能定位，响应体一并存着
            logAiCall(
                scene, subject, request.model, requestJson, startedAt, success = false,
                httpStatus = httpResponse.status, responseBody = body,
                errorMsg = "DeepSeek 返回 HTTP ${httpResponse.status}",
            )
            return null
        }

        val parsed = runCatching { objectMapper.readValue<DeepSeekResponse>(body) }.getOrElse {
            logAiCall(
                scene, subject, request.model, requestJson, startedAt, success = false,
                httpStatus = httpResponse.status, responseBody = body,
                errorMsg = "响应解析失败 :: ${it.message?.take(200)}",
            )
            return null
        }

        val content = parsed.choices?.firstOrNull()?.message?.content?.trim()?.takeIf { it.isNotBlank() }
        logAiCall(
            scene, subject, parsed.model ?: request.model, requestJson, startedAt,
            // HTTP 200 但没有正文（空 choices / 被 max_tokens 截成空串）对调用方而言同样是失败，
            // 日志里也该显示成失败，否则「成功率 100% 但结果全是兜底值」根本对不上
            success = content != null,
            httpStatus = httpResponse.status, responseBody = body, usage = parsed.usage,
            errorMsg = if (content == null) "响应未包含有效内容" else null,
        )
        return content
    }

    /** 记录一次对 DeepSeek 的调用；日志写入失败不影响主流程。 */
    private fun logAiCall(
        scene: AiCallScene,
        subject: String?,
        model: String?,
        requestBody: String?,
        startedAt: Long,
        success: Boolean,
        httpStatus: Int? = null,
        responseBody: String? = null,
        usage: DeepSeekUsage? = null,
        errorMsg: String? = null,
    ) {
        runCatching {
            aiCallLogMapper.insert(
                AiCallLogEntity(
                    scene = scene,
                    model = model,
                    subject = subject?.take(200),
                    success = success,
                    httpStatus = httpStatus,
                    requestBody = requestBody?.take(AiCallLogEntity.MAX_BODY_LEN),
                    responseBody = responseBody?.take(AiCallLogEntity.MAX_BODY_LEN),
                    promptTokens = usage?.promptTokens,
                    completionTokens = usage?.completionTokens,
                    totalTokens = usage?.totalTokens,
                    durationMs = System.currentTimeMillis() - startedAt,
                    errorMsg = errorMsg?.take(500),
                )
            )
        }.onFailure { log.warn("[logAiCall] 写入 AI 调用日志失败: ${it.message}") }
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

        // 模型偶尔会把"无法判断时返回空字符串"的指令误当作要输出的内容，直接吐出"空字符串"这几个字
        // 而非真正的空响应，导致该字面量被当作合法简称存入 appName 并覆盖真实标题。空白已由
        // chatCompletion 统一过滤，这里再挡掉约定的哨兵词 NONE 与历史上曾被污染的字面量"空字符串"。
        return chatCompletion(AiCallScene.APP_NAME, title, request)
            ?.takeIf { it != "NONE" && it != "空字符串" }
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

        val raw = chatCompletion(AiCallScene.CATEGORY_INFER, host, request) ?: return emptyList()

        return raw.split(',', '，', '\n', ' ')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it in allowed }
            .distinct()
    }

    override fun proposeCategories(
        title: String?,
        description: String?,
        host: String,
        existing: List<CategoryCandidate>,
    ): List<ProposedCategory> {
        val catalogue = existing
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { c -> "- ${c.slug}（${c.name}）${c.description?.let { "：$it" } ?: ""}" }
            ?: "（当前分类列表为空）"
        val systemPrompt = """
            你是一个网站分类助手。下面是系统里**已有**的分类（slug 及含义）：
            $catalogue
            根据用户给出的网站信息，给出 1~3 个最贴切的分类。
            规则：
            1. 已有分类能覆盖时必须复用已有的 slug，不要造近义的新词；
            2. 已有分类都不贴切时，可以新建分类：slug 用小写英文与连字符（如 ai-tools），名称用简短中文；
            3. 每行一个，格式严格为 `slug|中文名`，不要编号、解释、markdown 或任何额外文字。
        """.trimIndent()
        val userContent = "host: $host\ntitle: ${title ?: ""}\ndescription: ${description ?: ""}"

        val request = DeepSeekRequest(
            messages = listOf(
                DeepSeekMessage(role = "system", content = systemPrompt),
                DeepSeekMessage(role = "user", content = userContent),
            ),
            maxTokens = 120,
        )

        val raw = chatCompletion(AiCallScene.CATEGORY_PROPOSE, host, request, readTimeoutMs = 15_000)
            ?: return emptyList()

        return raw.lineSequence()
            .mapNotNull { line ->
                // 模型偶尔会写成 `slug｜中文名` 或加上 "- " 前缀，这里一并容错
                val parts = line.trim().removePrefix("-").trim().split('|', '｜', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val slug = normalizeSlug(parts[0])
                val name = parts[1].trim().take(MAX_CATEGORY_NAME_LEN)
                if (slug.isBlank() || name.isBlank()) null else ProposedCategory(slug, name)
            }
            .distinctBy { it.slug }
            .take(MAX_PROPOSED_CATEGORIES)
            .toList()
    }

    /**
     * 把模型给的 slug 削成库里能用的形态：小写、只留 `a-z0-9-`、收敛连续连字符。
     *
     * 不做这一步的话 `AI 工具` / `Ai_Tools` / `ai tools` 会各自建一条分类，字典很快就脏了。
     */
    private fun normalizeSlug(raw: String): String =
        raw.trim().lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(MAX_CATEGORY_SLUG_LEN)
            .trim('-')

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
        val content = chatCompletion(AiCallScene.SIMILAR_SITE, host, request, readTimeoutMs = 20_000)
            ?: return emptyList()
        // 最多返回 10 个，防止模型偶发超量
        return parseSimilarSites(content).take(10)
    }

    /** 解析 DeepSeek 返回的文本为相似网站列表：剥离 ```json 围栏后按 JSON 数组解析，失败返回空。 */
    internal fun parseSimilarSites(content: String): List<SimilarSite> {
        val json = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { objectMapper.readValue<List<SimilarSite>>(json) }.getOrElse { emptyList() }
    }

    /**
     * 活性探测。三态判定的归属与 [scrape] 完全一致，共用 [classifyScrapperError]：
     * 「目标站点打不开」(E304) 才是站点的事实，其余一律是我方链路的问题。
     *
     * 此前这里把所有失败路径都塌缩成 `false`，于是 scrapper 没起、token 配错、
     * 或并发超限被 load_shed 打回 503，都会被巡检任务当作「站点失联」写进 `bookmark`。
     * `scrape` 早就用 [isScrapperUnavailable][top.tcyeee.bookmarkify.server.impl.BookmarkServiceImpl]
     * 挡掉了同类问题，ping 这条路径一直是漏的——而它还是所有判定的第一道门。
     */
    override fun pingWebsite(url: String): PingOutcome {
        val targetUrl = buildUrl(url)
        val endpoint = "${scrapperConfig.baseUrl.trimEnd('/')}/ping"

        // 非域名目标不探测。返回 UNKNOWN 而不是抛异常，也不是 DEAD：
        // - 抛异常会炸掉整轮巡检（调用方在 CompletableFuture 里 join 这个结果）；
        // - DEAD 是"站点挂了"这个**关于站点的结论**，而我方压根没探，没有资格下结论。
        //   把"我们不去探"记成失联，会让这类书签在用户桌面上显示成失效。
        if (!ScrapeTargetGuard.isScrapable(targetUrl)) {
            log.warn("[pingWebsite] 目标不是域名(本机/IP)，跳过探测: url=$targetUrl")
            return PingOutcome.UNKNOWN
        }

        val httpResponse = runCatching {
            HttpUtil.createPost(endpoint)
                .header("Content-Type", "application/json")
                .withScrapperAuth()
                .body(objectMapper.writeValueAsString(PingRequest(url = targetUrl)))
                .timeouts(PING_READ_TIMEOUT_MS)
                .execute()
        }.getOrElse {
            // 传输层异常必然发生在 API ↔ scrapper 之间：目标站点能否打开是由 scrapper
            // 判定后写在响应体里的，我方连响应都没拿到，对站点死活没有任何结论
            log.warn("[pingWebsite] 抓取服务不可达，本次探测无结论: url=$targetUrl, endpoint=$endpoint, err=${it.message}$scrapperStartupHint")
            return PingOutcome.UNKNOWN
        }

        if (!httpResponse.isOk) {
            val code = runCatching { objectMapper.readTree(httpResponse.body())?.path("error")?.asText(null) }.getOrNull()
            return when (classifyScrapperError(code)) {
                // scrapper 明确判定目标站点打不开
                ErrorType.E304 -> PingOutcome.DEAD
                // URL 本身不合法：这条记录永远 ping 不通，结论确实是 DEAD，
                // 但成因是我方数据脏而非站点挂了，值得单独留一条日志
                ErrorType.E305 -> {
                    log.warn("[pingWebsite] URL 非法，判定为不可达: url=$targetUrl")
                    PingOutcome.DEAD
                }
                // scrapper 拒绝去探（内网地址 / 非域名目标）：这是我方的决定，不是站点的事实，
                // 没有资格判死。正常情况下走不到这里——本方法开头已经拦过一次同样的规则，而
                // scrapper 侧的 /ping 也是以 200 + `blocked: true` 报拒绝（那样才不需要部署
                // 顺序，见对端 PingResponse），不走这条非 2xx 分支。留着纯属兜底
                ErrorType.E308 -> {
                    log.warn("[pingWebsite] 抓取服务拒绝探测该目标，本次无结论: url=$targetUrl, code=$code")
                    PingOutcome.UNKNOWN
                }
                // 鉴权失败 / 并发超限的 503 / 请求体契约不符 —— 全是我方的问题
                else -> {
                    log.warn("[pingWebsite] 抓取服务返回异常，本次探测无结论: url=$targetUrl, status=${httpResponse.status}, code=$code")
                    PingOutcome.UNKNOWN
                }
            }
        }

        val body = runCatching { objectMapper.readValue<PingResponse>(httpResponse.body()) }.getOrElse {
            // 契约对不上是我方两侧代码不同步，同样不是站点的问题
            log.warn("[pingWebsite] 响应解析失败(契约不匹配)，本次探测无结论: url=$targetUrl, err=${it.message}")
            return PingOutcome.UNKNOWN
        }

        // 新契约：scrapper 只报事实，判死由 LivenessPolicy 决定
        body.reachable?.let { reachable ->
            val outcome = LivenessPolicy.outcomeOf(reachable, body.status, body.blocked)
            if (outcome != PingOutcome.ALIVE) log.debug(
                "[pingWebsite] $outcome: url=$targetUrl, reachable=$reachable, status=${body.status}, " +
                    "blocked=${body.blocked}, method=${body.method}, redirects=${body.redirects}"
            )
            return outcome
        }

        // 旧版 scrapper（只有一个 alive 布尔）。两个服务各自独立发布，必然有版本错配的窗口；
        // 这条回退只是把改造前的行为原样保留，拿不到状态码就没法分辨 404 与 200。
        // 线上 scrapper 全部升级后可以连同 PingResponse.alive 一起删除。
        body.alive?.let { alive ->
            log.debug("[pingWebsite] 对端为旧版 scrapper，退回粗粒度判定: url=$targetUrl, alive=$alive")
            return if (alive) PingOutcome.ALIVE else PingOutcome.DEAD
        }

        log.warn("[pingWebsite] 响应里既无 reachable 也无 alive，本次探测无结论: url=$targetUrl")
        return PingOutcome.UNKNOWN
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

        // fail-open：调用失败与「模型说没问题」在这里同样放行，不误伤正常网站
        val raw = chatCompletion(AiCallScene.NSFW_CHECK, host, request) ?: return NsfwCheckResult(false)

        return if (raw.lowercase().startsWith("no")) NsfwCheckResult(false) else NsfwCheckResult(true, raw)
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

        // fail-closed：拿不到结论时返回 Unavailable，绝不能与「内容正常」混为一谈
        val raw = chatCompletion(AiCallScene.CONTENT_REVIEW, host, request) ?: return AiReviewOutcome.Unavailable

        return if (raw.equals("OK", ignoreCase = true)) AiReviewOutcome.Pass else AiReviewOutcome.Rejected(raw)
    }

    private fun buildUrl(domain: String): String {
        if (domain.matches(Regex("^https?://.*"))) return domain
        return "https://$domain"
    }

    companion object {
        /**
         * 建立 TCP 连接的超时。对所有外部调用统一取值：连不上是网络/服务层面的硬失败，
         * 跟对方要花多久处理请求无关，没有哪个调用点需要更长的连接等待。
         */
        private const val CONNECT_TIMEOUT_MS = 5_000

        /** ping 的读取超时。对面只发一个 HEAD 请求，给 15s 已经很宽裕。 */
        private const val PING_READ_TIMEOUT_MS = 15_000

        private const val DEEPSEEK_ENDPOINT = "https://api.deepseek.com/chat/completions"

        /** AI 调用的默认读取超时。输出长的场景（相似网站、分类提议）在调用处单独放宽。 */
        private const val AI_READ_TIMEOUT_MS = 10_000

        /** 一个网站最多提议几个分类。与闭词表那条的 1~3 保持一致，别让一个站挂满标签。 */
        private const val MAX_PROPOSED_CATEGORIES = 3

        /** slug / name 的截断长度，与 `category` 表列宽对齐 */
        private const val MAX_CATEGORY_SLUG_LEN = 50
        private const val MAX_CATEGORY_NAME_LEN = 20
    }
}
