package top.tcyeee.bookmarkify.utils

import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType

/**
 * 「这个网址值不值得抓」的唯一判据：**只有域名才抓**，`localhost` / `127.0.0.1` / 裸 IP 一律不抓。
 *
 * ## 与 [SsrfGuard] 的分工
 *
 * 两者都会拒绝一部分网址，但回答的是不同的问题，不能互相替代：
 *
 * | | [SsrfGuard] | 本类 |
 * |---|---|---|
 * | 问题 | 这个地址会不会打到**我方**内网 | 这个目标是不是一个**网站** |
 * | 判据 | DNS 解析结果落在私有/环回/链路本地段 | host 的字面形态（IP 字面量 / localhost / 域名） |
 * | 公网裸 IP `47.97.71.143:8001` | 放行（它确实不是内网） | 拒绝（它不是网站） |
 * | 域名 A 记录指向 `10.0.0.1` | 拒绝 | 放行（形态上是域名，安全由前者兜） |
 *
 * 公网裸 IP 那一格就是本类存在的理由：那类地址是别人的内网服务暴露在公网的端口，抓回来
 * 只会是一个登录页或一个 404，标题/图标/OG 图对书签没有任何价值 —— 却要付一次完整的
 * 取回、甚至无头浏览器回退的代价。
 *
 * ## 为什么必须放在 API 而不只是 scrapper
 *
 * scrapper 侧同样有这道门（`scraper.rs::validate_target_is_domain`），但那是**最后**一道：
 * 请求已经跨了一次服务、在 `scrapper_call_log` 里留下一条失败记录、还把该页面的
 * `consecutive_fail` 推上去了一格。2026-08-08 生产上那条 `http://127.0.0.1:5000/ → 403
 * FORBIDDEN_TARGET` 就是这么来的。真正该做的是**根本不要发出去**。
 *
 * ## 为什么放在 [top.tcyeee.bookmarkify.server.impl.ApiServiceImpl] 而不是每个调用点
 *
 * `parseBookmarkExclusively` 早就按 [BookmarkLinkType] 过滤了，可它只是**一条**路径：
 * 后台的重新获取/一键更新/重抓资产/活性检测、截图补抓、调试抓取，六个入口都直接调
 * `apiService.scrape(bookmark.rawUrl, …)`，一个都没带这层判断。逐个补等于把同一条规则
 * 抄六遍，下一个新入口照样会漏 —— 所以判断落在所有人都必经的那个方法上。
 */
object ScrapeTargetGuard {

    /**
     * 判断这个网址的链接类型。解析不出 host 时返回 [BookmarkLinkType.OTHER]
     * ——「认不出来」和「不是域名」在这里的处置一致：都不抓。
     */
    fun linkTypeOf(url: String): BookmarkLinkType =
        runCatching { WebsiteParser.classifyLinkType(hostOf(url)) }.getOrDefault(BookmarkLinkType.OTHER)

    /** 这个网址能不能作为抓取目标（只有域名可以）。 */
    fun isScrapable(url: String): Boolean = linkTypeOf(url) == BookmarkLinkType.DOMAIN

    /**
     * 断言这个网址可以抓，否则抛 [ErrorType.E309]。
     *
     * 报错文案会原样透给管理员（后台的抓取按钮直接展示 message），所以说清三件事：
     * 拒的是哪个地址、它被判成了什么、以及为什么这不是一次失败而是一次**拒绝执行**。
     */
    fun assertScrapable(url: String) {
        val type = linkTypeOf(url)
        if (type == BookmarkLinkType.DOMAIN) return
        val reason = when (type) {
            BookmarkLinkType.LOCAL -> "指向本机(localhost/127.0.0.1)"
            BookmarkLinkType.IP -> "是 IP 地址而非域名"
            else -> "不是一个可抓取的域名"
        }
        throw CommonException(ErrorType.E309, "已拒绝抓取 $url ：该地址$reason，抓取它得不到任何有意义的信息")
    }

    /**
     * 从网址里取出 host（含端口，交给 [WebsiteParser.classifyLinkType] 自己剥）。
     *
     * 不走 [WebsiteParser.urlWrapper]：那个方法带着长度校验、规范化等一整套业务规则，还会抛
     * [ErrorType.E127]，用它做一次形态判断属于杀鸡用牛刀，而且会把「网址过长」的错误伪装成
     * 「不是域名」。
     *
     * **也不走 [java.net.URI]**，尽管那是这里最自然的选择：单参构造器严格按 RFC 2396 校验
     * 整条网址，`|`、空格、`^`、`{}`、`\`、`"`、`<>`、反引号出现在 path/query 里就抛
     * `URISyntaxException` —— 而这些字符在真实网址里天天出现，
     * `https://fonts.googleapis.com/css?family=Roboto|Open+Sans` 就是一条。它们进不了这里的
     * 异常处理：[linkTypeOf] 把解析失败兜成 [BookmarkLinkType.OTHER]，与「这是个 IP」无从区分，
     * 于是一个好端端的域名被 [assertScrapable] 判成"不是域名"，永久不抓也不巡检。
     * [WebsiteParser.urlWrapper] 那条链路（hutool `toUrlForHttp` 只编码空格）不做百分号编码，
     * 这些字符会原样落进 `page.raw_url`，所以这不是理论情形。
     *
     * host 的位置由分隔符唯一确定，不需要合法性校验：剥协议 → 截到第一个 `/`、`?`、`#` →
     * 去掉 userinfo。端口原样留着，`classifyLinkType` 自己会剥（它同时认得 `[::1]:8080`）。
     */
    private fun hostOf(url: String): String {
        val raw = url.trim()
        // 只在确实带协议时剥：无协议的 `example.com/a://b` 用 substringAfter("://") 会切错
        val afterScheme = if (raw.matches(SCHEME_RE)) raw.substringAfter("://") else raw
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@')
    }

    private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*", RegexOption.DOT_MATCHES_ALL)
}
