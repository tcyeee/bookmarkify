package top.tcyeee.bookmarkify.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 校验 urlWrapper 的 host/path 解析：canonical 书签按 (urlHost, urlPath) 去重，
 * 同域名不同路径必须产生不同的 key，否则会退化回「同域名共用同一份抓取结果」的 bug。
 */
class WebsiteParserTest {

    @Test
    fun `different paths under the same host produce different urlPath`() {
        val a = WebsiteParser.urlWrapper("https://github.com/facebook/react")
        val b = WebsiteParser.urlWrapper("https://github.com/torvalds/linux")
        assertEquals(a.urlHost, b.urlHost)
        assertNotEquals(a.urlPath, b.urlPath)
        assertNotEquals(a.urlFull, b.urlFull)
    }

    @Test
    fun `root path with and without trailing slash normalize to the same key`() {
        val bare = WebsiteParser.urlWrapper("https://example.com")
        val slash = WebsiteParser.urlWrapper("https://example.com/")
        assertEquals("/", bare.urlPath)
        assertEquals("/", slash.urlPath)
        assertEquals(bare.urlFull, slash.urlFull)
    }

    @Test
    fun `trailing slash on a non-root path is normalized away`() {
        val withSlash = WebsiteParser.urlWrapper("https://example.com/docs/")
        val withoutSlash = WebsiteParser.urlWrapper("https://example.com/docs")
        assertEquals(withoutSlash.urlPath, withSlash.urlPath)
        assertEquals(withoutSlash.urlFull, withSlash.urlFull)
    }

    @Test
    fun `different subdomains produce different urlHost`() {
        val a = WebsiteParser.urlWrapper("https://a.notion.site/Page-1")
        val b = WebsiteParser.urlWrapper("https://b.notion.site/Page-2")
        assertNotEquals(a.urlHost, b.urlHost)
    }

    // ────── query 进 key（详见 UrlCanonicalizerTest，这里只钉住 urlWrapper 的接线） ──────

    /**
     * 此前 query 被整个丢掉，两个视频收敛成一条记录，抓取目标还退化成不存在的 `/watch`。
     * 这条用例同时钉住三件事：query 进 urlQuery、query 进 urlFull（抓取目标）、两者不相等。
     */
    @Test
    fun `query participates in the canonical key and in the fetch target`() {
        val a = WebsiteParser.urlWrapper("https://www.youtube.com/watch?v=A")
        val b = WebsiteParser.urlWrapper("https://www.youtube.com/watch?v=B")
        assertEquals("v=A", a.urlQuery)
        assertEquals(a.urlPath, b.urlPath)
        assertNotEquals(a.urlQuery, b.urlQuery)
        assertEquals("https://www.youtube.com/watch?v=A", a.urlFull)
        assertNotEquals(a.urlFull, b.urlFull)
    }

    @Test
    fun `tracking params do not create a separate canonical record`() {
        val plain = WebsiteParser.urlWrapper("https://www.youtube.com/watch?v=A")
        val shared = WebsiteParser.urlWrapper("https://www.youtube.com/watch?v=A&utm_source=wechat")
        assertEquals(plain.urlQuery, shared.urlQuery)
        assertEquals(plain.urlFull, shared.urlFull)
    }

    @Test
    fun `hash route participates in the key but a page anchor does not`() {
        val route = WebsiteParser.urlWrapper("https://app.example.com/#/settings")
        assertEquals("/settings", route.urlFragment)
        assertEquals("https://app.example.com/#/settings", route.urlFull)

        val anchor = WebsiteParser.urlWrapper("https://example.com/docs#install")
        assertEquals("", anchor.urlFragment)
        assertEquals("https://example.com/docs", anchor.urlFull)
    }

    @Test
    fun `port is preserved in the fetch target`() {
        // 拼掉端口就指向了另一个服务：localhost:3000 的书签会去抓 localhost:80
        val w = WebsiteParser.urlWrapper("http://localhost:3000/dash")
        assertEquals("localhost:3000", w.urlHost)
        assertEquals("http://localhost:3000/dash", w.urlFull)
    }
}
