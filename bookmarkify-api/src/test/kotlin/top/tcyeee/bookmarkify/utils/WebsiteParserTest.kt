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
}
