package top.tcyeee.bookmarkify.utils

import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 「只有域名才抓」这条规则的判据。纯字符串形态判断，不做 DNS，因此在无外网的构建机上同样可靠。
 */
class ScrapeTargetGuardTest {

    private fun assertRefused(url: String) {
        val ex = assertFailsWith<CommonException>("应当拒绝抓取: $url") { ScrapeTargetGuard.assertScrapable(url) }
        assertEquals(ErrorType.E309, ex.errorType, "拒绝理由应当是「不是域名」而不是别的: $url")
        assertTrue(ex.customMessage?.contains(url) == true, "报错要点名是哪个地址被拒了: $url")
    }

    @Test
    fun `refuses localhost in every spelling`() {
        assertRefused("http://localhost:5173/")
        assertRefused("https://LOCALHOST/dashboard")
        assertRefused("http://127.0.0.1:5000/")
        assertRefused("http://[::1]:8080/")
    }

    /**
     * 本次改动的核心：公网裸 IP。SSRF 那道门对它一路放行（它确实不是内网），
     * 于是在此之前 `http://47.97.71.143:8001/` 会被真的抓一次 —— 抓回来是一个登录页。
     */
    @Test
    fun `refuses public ip literals too`() {
        assertRefused("http://47.97.71.143:8001/")
        assertRefused("http://122.228.64.2:6005/view/develop")
        assertRefused("http://192.168.0.73:8192/login")
    }

    @Test
    fun `allows ordinary domains`() {
        for (url in listOf(
            "https://example.com/",
            "https://www.bilibili.com/video/BV1xx411c7mD",
            "http://localhost.example.com/", // 主机名里含 localhost，但它是个正经域名
            "example.com/no-scheme",         // 缺协议时按 https 补全后再判断
        )) {
            ScrapeTargetGuard.assertScrapable(url)
            assertTrue(ScrapeTargetGuard.isScrapable(url), "应当放行: $url")
        }
    }

    /**
     * 回归：path/query 里带 RFC 2396 眼中的非法字符，仍然是个域名。
     *
     * 这些字符（`|` `空格` `^` `{}` `\` `"` `<>` 反引号）在真实网址里天天出现，而
     * `URI(String)` 会对整条网址抛 `URISyntaxException`。[ScrapeTargetGuard.linkTypeOf]
     * 把解析失败兜成 OTHER，与「这是个 IP」无从区分，于是一条好域名会被判成"不是域名"
     * 而**永久**不抓不巡检。`WebsiteParser.urlWrapper` 那条链路不做百分号编码，这些字符
     * 会原样落进 `page.raw_url`，所以这不是理论情形。
     */
    @Test
    fun `allows domains whose path or query carries characters URI would reject`() {
        for (url in listOf(
            "https://fonts.googleapis.com/css?family=Roboto|Open+Sans",
            "https://example.com/a b/c",
            "https://example.com/search?q={keyword}",
            "https://example.com/path^weird",
            "https://example.com/x\\y",
            "https://example.com/?q=<tag>",
        )) {
            assertTrue(ScrapeTargetGuard.isScrapable(url), "应当放行: $url")
        }
    }

    /** userinfo 与端口都不该影响判定：host 才是判据。 */
    @Test
    fun `strips userinfo and keeps port out of the way`() {
        assertTrue(ScrapeTargetGuard.isScrapable("https://user:pass@example.com:8443/x"))
        assertRefused("https://user:pass@127.0.0.1:8443/x")
    }

    /**
     * 解析不出主机名时一律拒绝。「认不出来」和「不是域名」在这里处置一致：都不发请求。
     * 拿不到结论就不放行，是这类检查唯一安全的失败方向。
     */
    @Test
    fun `refuses garbage input instead of passing it through`() {
        assertRefused("not a url at all")
        assertRefused("https://")
    }
}
