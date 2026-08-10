package top.tcyeee.bookmarkify.entity.dto.scrape

import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 校验 [applyTo] 里的协议回写。
 *
 * `page.urlScheme` 取自用户当初提交的那个网址，此前从不回写。一条当年用 `http://` 收藏、
 * 站点后来全站上了 https 的书签，会永远以 http 为抓取目标（`page.rawUrl` 是拿这一列拼的），
 * 每次抓取和活性探测都先白吃一跳 301。终点一直在 `fetch.finalUrl` 里，只是没人读。
 *
 * 这里测的全是**不该动**的那几种情况：判据只要放松一点，要么把 https 记录降级，
 * 要么把「这条记录指向了另一个 host」伪装成「协议升级」。
 */
class ScrapeResponseApplyToTest {

    private fun page(scheme: String, host: String = "example.com") = PageEntity(
        BookmarkUrlWrapper(
            urlRaw = "$scheme://$host/",
            urlScheme = scheme,
            urlHost = host,
            urlRoot = "$scheme://$host",
            urlFull = "$scheme://$host/",
            urlPath = "/",
            urlQuery = "",
        ),
        siteId = "site-example",
    )

    private fun response(finalUrl: String) =
        ScrapeResponse(fetch = FetchInfo(finalUrl = finalUrl, httpStatus = 200))

    @Test
    fun `http page landing on https is upgraded`() {
        val page = page("http")
        response("https://example.com/").applyTo(page)
        assertEquals("https", page.urlScheme)
        // rawUrl 是抓取与 ping 的目标，升级的全部意义就在这里
        assertEquals("https://example.com/", page.rawUrl)
    }

    /** 只升不降：一次落在 http 的抓取不该把 https 记录往回退一档 */
    @Test
    fun `https page landing on http is left alone`() {
        val page = page("https")
        response("http://example.com/").applyTo(page)
        assertEquals("https", page.urlScheme)
    }

    /** 真·http-only 的站点，终点也是 http，无事可做 */
    @Test
    fun `http page landing on http stays http`() {
        val page = page("http")
        response("http://example.com/").applyTo(page)
        assertEquals("http", page.urlScheme)
    }

    /**
     * authority 变了说明这条记录指向的根本是另一个 host —— 那是 canonical 重定向的问题。
     * 顺手把 scheme 改掉只会让一条指错地方的记录看起来像是修好了。
     */
    @Test
    fun `redirect to another host does not upgrade the scheme`() {
        val page = page("http")
        response("https://www.example.com/").applyTo(page)
        assertEquals("http", page.urlScheme)
    }

    /** 端口是 authority 的一部分：带端口的记录与不带端口的终点不算同一个 host */
    @Test
    fun `port mismatch does not upgrade the scheme`() {
        val page = page("http", host = "example.com:8080")
        response("https://example.com/").applyTo(page)
        assertEquals("http", page.urlScheme)
    }

    /** finalUrl 解析不出来时保持原样，不能让一个畸形地址把这一列写坏 */
    @Test
    fun `malformed final url is ignored`() {
        val page = page("http")
        response("not a url").applyTo(page)
        assertEquals("http", page.urlScheme)
    }
}
