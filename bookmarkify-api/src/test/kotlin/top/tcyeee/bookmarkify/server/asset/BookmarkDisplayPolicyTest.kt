package top.tcyeee.bookmarkify.server.asset

import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 文案优先级。这里的用例分两类，两类同等重要：
 * - **用户标题永远第一**：改造前 `appName ?: title` 把站点短名压在用户标题之上，用户改完名字
 *   看不到任何变化。
 * - **深链不能显示站点短名**：一屏 20 个 YouTube 视频磁贴全叫 "YouTube" 就等于没有文案。
 */
class BookmarkDisplayPolicyTest {

    private fun title(
        userTitle: String? = null,
        pageTitle: String? = null,
        siteShortName: String? = null,
        pageAppName: String? = null,
        siteBrandName: String? = null,
        urlHost: String? = "www.youtube.com",
        isRootPage: Boolean,
        mode: DisplayMode,
    ) = BookmarkDisplayPolicy.title(
        userTitle, pageTitle, siteShortName, pageAppName, siteBrandName, urlHost, isRootPage, mode,
    )

    // ────── 用户标题永远第一 ──────

    @Test
    fun `user title beats everything in every combination`() {
        for (mode in DisplayMode.entries) {
            for (root in listOf(true, false)) {
                assertEquals(
                    "我的收藏",
                    title(
                        userTitle = "我的收藏",
                        pageTitle = "页面标题",
                        siteShortName = "YouTube",
                        siteBrandName = "YouTube 品牌名",
                        isRootPage = root,
                        mode = mode,
                    ),
                    "mode=$mode root=$root 时用户标题被顶掉了",
                )
            }
        }
    }

    /** 空白的用户标题不算"改过"，不该把格子变成空白 */
    @Test
    fun `blank user title falls through instead of rendering empty`() {
        assertEquals(
            "YouTube",
            title(userTitle = "   ", siteShortName = "YouTube", isRootPage = true, mode = DisplayMode.TILE),
        )
    }

    // ────── 首页：短名只在这里胜出 ──────

    @Test
    fun `homepage tile prefers the short site name`() {
        assertEquals(
            "YouTube",
            title(
                pageTitle = "YouTube - 免费视频分享平台，供全球用户上传观看",
                siteShortName = "YouTube",
                siteBrandName = "YouTube 品牌名",
                isRootPage = true,
                mode = DisplayMode.TILE,
            ),
            "磁贴空间只有一行，首页就该用 manifest.short_name",
        )
    }

    /**
     * 生产上 92 个首页里只有 15 个有 manifest 短名，而 75 个有 `page.app_name`（DeepSeek 从标题
     * 推断的简称）。没有这一档，磁贴上「短名」这条规则对 84% 的书签形同虚设 —— bilibili 首页
     * 会一路掉到裸域名 `www.bilibili.com`。
     */
    @Test
    fun `homepage tile falls back to the page app name when the site has no manifest short name`() {
        assertEquals(
            "哔哩哔哩",
            title(
                pageTitle = "哔哩哔哩 (゜-゜)つロ 干杯~-bilibili",
                siteShortName = null,
                pageAppName = "哔哩哔哩",
                urlHost = "www.bilibili.com",
                isRootPage = true,
                mode = DisplayMode.TILE,
            ),
        )
    }

    /** 站点短名是首页抓取权威写入的，压在逐页 LLM 推断之上 */
    @Test
    fun `site short name outranks the page app name`() {
        assertEquals(
            "YouTube",
            title(siteShortName = "YouTube", pageAppName = "油管", isRootPage = true, mode = DisplayMode.TILE),
        )
    }

    /** 列表行原封不动：有完整空间，就该显示页面标题，简称不参与 */
    @Test
    fun `list mode ignores the page app name entirely`() {
        assertEquals(
            "哔哩哔哩 (゜-゜)つロ 干杯~-bilibili",
            title(
                pageTitle = "哔哩哔哩 (゜-゜)つロ 干杯~-bilibili",
                pageAppName = "哔哩哔哩",
                isRootPage = true,
                mode = DisplayMode.LIST,
            ),
        )
        assertEquals(
            "www.youtube.com",
            title(pageAppName = "油管", isRootPage = false, mode = DisplayMode.LIST),
            "深链列表行抓不到页面标题就退到域名，简称不该顶上来",
        )
    }

    @Test
    fun `homepage list prefers the full page title`() {
        assertEquals(
            "YouTube - 免费视频分享平台",
            title(
                pageTitle = "YouTube - 免费视频分享平台",
                siteShortName = "YouTube",
                isRootPage = true,
                mode = DisplayMode.LIST,
            ),
        )
    }

    // ────── 深链：必须用页面标题 ──────

    /**
     * 这条是整个策略存在的理由。跨站点靠图标区分，同站点内图标完全一样、只能靠文案区分，
     * 所以深链的文案必须是页面级的 —— 哪怕是在空间受限的磁贴上。
     */
    @Test
    fun `deep link tile uses the page title so sibling tiles stay distinguishable`() {
        val a = title(
            pageTitle = "第一个视频", siteShortName = "YouTube", isRootPage = false, mode = DisplayMode.TILE,
        )
        val b = title(
            pageTitle = "第二个视频", siteShortName = "YouTube", isRootPage = false, mode = DisplayMode.TILE,
        )
        assertEquals("第一个视频", a)
        assertEquals("第二个视频", b)
    }

    @Test
    fun `deep link tile falls back to the short name when the page title is missing`() {
        // 页面标题没抓到时，"YouTube" 仍然好于裸域名
        assertEquals(
            "YouTube",
            title(siteShortName = "YouTube", isRootPage = false, mode = DisplayMode.TILE),
        )
    }

    @Test
    fun `deep link list never shows the site short name`() {
        // 列表行有完整空间，抓不到页面标题就直接退到域名；显示 "YouTube" 会误导成这是首页
        assertEquals(
            "www.youtube.com",
            title(siteShortName = "YouTube", isRootPage = false, mode = DisplayMode.LIST),
        )
    }

    // ────── 兜底 ──────

    @Test
    fun `host is the last resort so a tile is never blank`() {
        for (mode in DisplayMode.entries) {
            for (root in listOf(true, false)) {
                assertEquals("www.youtube.com", title(isRootPage = root, mode = mode))
            }
        }
    }

    @Test
    fun `everything missing yields null rather than an empty string`() {
        assertEquals(
            null,
            title(urlHost = null, isRootPage = true, mode = DisplayMode.TILE),
            "全空时返回 null，让调用方自己决定怎么兜底，而不是下发一个空串",
        )
    }

    /** 没有 canonical 记录的无源书签（javascript: 之类）：只有用户标题可用 */
    @Test
    fun `a bookmark without any crawled page still shows the user title`() {
        assertEquals(
            "我手写的",
            title(userTitle = "我手写的", urlHost = null, isRootPage = true, mode = DisplayMode.TILE),
        )
    }
}
