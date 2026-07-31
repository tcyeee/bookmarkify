package top.tcyeee.bookmarkify.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * canonical key 的收敛规则。这里的每条用例都对应一种"两个写法是不是同一个页面"的判断，
 * 判错的代价是两种：合错了 → 两个页面共用一份抓取结果（数据错误）；漏合了 → 多抓一次（浪费）。
 * 所以**该合的用例和不该合的用例同等重要**，成对出现。
 */
class UrlCanonicalizerTest {

    // ────── query 参与 key：本次重构要修的核心 bug ──────

    @Test
    fun `different query values under the same path produce different keys`() {
        val a = UrlCanonicalizer.canonicalize("/watch", "v=A", null)
        val b = UrlCanonicalizer.canonicalize("/watch", "v=B", null)
        assertEquals(a.path, b.path)
        assertNotEquals(a.query, b.query)
        assertNotEquals(
            a.rawUrl("https", "www.youtube.com"),
            b.rawUrl("https", "www.youtube.com"),
        )
    }

    @Test
    fun `raw url keeps the query so the scrapper fetches the real page`() {
        val parts = UrlCanonicalizer.canonicalize("/watch", "v=dQw4w9WgXcQ", null)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", parts.rawUrl("https", "www.youtube.com"))
    }

    @Test
    fun `param order does not affect the key`() {
        val a = UrlCanonicalizer.normalizeQuery("v=A&t=10")
        val b = UrlCanonicalizer.normalizeQuery("t=10&v=A")
        assertEquals(a, b)
    }

    // ────── 追踪参数剥离 ──────

    @Test
    fun `tracking params are stripped so shared links converge`() {
        val plain = UrlCanonicalizer.normalizeQuery("v=A")
        val shared = UrlCanonicalizer.normalizeQuery("v=A&utm_source=wechat&utm_medium=social&spm=a2h.1")
        assertEquals(plain, shared)
    }

    @Test
    fun `a url made entirely of tracking params normalizes to an empty query`() {
        assertEquals("", UrlCanonicalizer.normalizeQuery("utm_source=x&fbclid=y&gclid=z"))
    }

    /**
     * 黑名单保守性的回归护栏：这几个名字看着像追踪参数，但在真实站点上是业务参数。
     * 剥错一个就把两个不同页面合并成一条 canonical 记录 —— 比多留一个参数严重得多。
     */
    @Test
    fun `business params that look like tracking params are kept`() {
        listOf("from=2024", "source=manual", "ref=main", "referrer=x", "timestamp=1700000000", "id=42")
            .forEach { assertEquals(it, UrlCanonicalizer.normalizeQuery(it), "误剥了业务参数: $it") }
    }

    @Test
    fun `wordpress style search query is not stripped`() {
        // `?s=` 是 WordPress 站内搜索；剥掉会把所有搜索结果页合并成首页
        assertEquals("s=kotlin", UrlCanonicalizer.normalizeQuery("s=kotlin"))
    }

    @Test
    fun `tracking param matching is case insensitive`() {
        assertEquals("v=A", UrlCanonicalizer.normalizeQuery("v=A&UTM_Source=x&FBCLID=y"))
    }

    // ────── path ──────

    @Test
    fun `blank and root path both normalize to slash`() {
        assertEquals("/", UrlCanonicalizer.normalizePath(null))
        assertEquals("/", UrlCanonicalizer.normalizePath(""))
        assertEquals("/", UrlCanonicalizer.normalizePath("/"))
    }

    @Test
    fun `trailing slash on a non-root path is normalized away`() {
        assertEquals("/docs", UrlCanonicalizer.normalizePath("/docs/"))
        assertEquals(UrlCanonicalizer.normalizePath("/docs"), UrlCanonicalizer.normalizePath("/docs/"))
    }

    @Test
    fun `different paths stay different`() {
        assertNotEquals(
            UrlCanonicalizer.normalizePath("/facebook/react"),
            UrlCanonicalizer.normalizePath("/torvalds/linux"),
        )
    }

    // ────── fragment：路由型保留，锚点型丢弃 ──────

    @Test
    fun `anchor fragments are discarded so anchors of one page share a record`() {
        assertEquals("", UrlCanonicalizer.normalizeFragment("comments"))
        assertEquals("", UrlCanonicalizer.normalizeFragment("L42"))
        assertEquals("", UrlCanonicalizer.normalizeFragment("section-3"))
    }

    @Test
    fun `hash routes are kept so a hash-routed SPA does not collapse into one record`() {
        val docs = UrlCanonicalizer.canonicalize("/", null, "/docs/intro")
        val about = UrlCanonicalizer.canonicalize("/", null, "/about")
        assertEquals("/", docs.path)
        assertNotEquals(docs.fragment, about.fragment)
        assertEquals("https://a.com/#/docs/intro", docs.rawUrl("https", "a.com"))
    }

    @Test
    fun `hashbang is equivalent to a hash route`() {
        assertEquals(
            UrlCanonicalizer.normalizeFragment("/inbox"),
            UrlCanonicalizer.normalizeFragment("!/inbox"),
        )
    }

    @Test
    fun `query inside a hash route is normalized too`() {
        val a = UrlCanonicalizer.normalizeFragment("/list?b=2&a=1&utm_source=x")
        assertEquals("/list?a=1&b=2", a)
    }

    /**
     * fragment 之所以单独成列：真 query 和 hash query 同时存在时，折进 path 就拼不回正确顺序了
     * （`?` 必须在 `#` 前，hash 内的 `?` 必须在 `#` 后）。
     */
    @Test
    fun `a url with both a real query and a hash query round-trips correctly`() {
        val parts = UrlCanonicalizer.canonicalize("/app", "x=1", "/docs?y=2")
        assertEquals("https://a.com/app?x=1#/docs?y=2", parts.rawUrl("https", "a.com"))
    }

    // ────── isRoot：展示策略按它分叉 ──────

    @Test
    fun `isRoot distinguishes a site homepage from a deep link`() {
        assertTrue(UrlCanonicalizer.canonicalize("/", null, null).isRoot)
        assertTrue(UrlCanonicalizer.canonicalize("", "", "").isRoot)
        // 锚点被丢弃，带锚点的首页仍然是首页
        assertTrue(UrlCanonicalizer.canonicalize("/", null, "top").isRoot)
        assertFalse(UrlCanonicalizer.canonicalize("/watch", "v=A", null).isRoot)
        assertFalse(UrlCanonicalizer.canonicalize("/docs", null, null).isRoot)
        assertFalse(UrlCanonicalizer.canonicalize("/", null, "/docs").isRoot)
        // 只带追踪参数的首页链接，剥完之后还是首页
        assertTrue(UrlCanonicalizer.canonicalize("/", "utm_source=x", null).isRoot)
    }

    @Test
    fun `empty query and fragment render a bare root url`() {
        assertEquals("https://a.com/", UrlCanonicalizer.canonicalize("/", null, null).rawUrl("https", "a.com"))
    }
}
