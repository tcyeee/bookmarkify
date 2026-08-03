package top.tcyeee.bookmarkify.server.asset

import top.tcyeee.bookmarkify.entity.dto.scrape.AssetExtractor
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `extractor → role` 策略与按展示模式选图的规则。
 *
 * 这是整套重构里唯一带业务判断的部分，因此测试覆盖得比别处细。
 */
class AssetRolePolicyTest {

    private fun asset(
        extractor: AssetExtractor,
        size: Int? = null,
        hash: String? = null,
        vector: Boolean = false,
        url: String = "https://cdn.example.com/${extractor.name.lowercase()}.png",
        error: String? = null,
    ) = SiteAssetEntity(
        bookmarkId = "bm-1",
        extractor = extractor.name,
        originUrl = url,
        resolvedUrl = url,
        width = size,
        height = size,
        isVector = vector,
        contentHash = hash,
        errorMsg = error,
    )

    // ── 映射表 ──────────────────────────────────────────────────────────────

    @Test
    fun `json-ld org logo and manifest icon are the only trusted logos`() {
        assertEquals(
            AssetRole.LOGO to AssetQuality.TRUSTED,
            AssetRolePolicy.classify(AssetExtractor.JSON_LD_ORG_LOGO)
        )
        assertEquals(
            AssetRole.LOGO to AssetQuality.TRUSTED,
            AssetRolePolicy.classify(AssetExtractor.MANIFEST_ICON)
        )
    }

    @Test
    fun `icon family maps to favicon, not logo`() {
        for (e in listOf(
            AssetExtractor.LINK_ICON,
            AssetExtractor.LINK_MASK_ICON,
            AssetExtractor.APPLE_TOUCH_ICON,
            AssetExtractor.FAVICON_ICO_FALLBACK,
        )) {
            assertEquals(AssetRole.FAVICON, AssetRolePolicy.classify(e).first, "$e 应归为 FAVICON")
        }
    }

    @Test
    fun `social images come from og and twitter`() {
        assertEquals(AssetRole.SOCIAL, AssetRolePolicy.classify(AssetExtractor.OG_IMAGE).first)
        assertEquals(AssetRole.SOCIAL, AssetRolePolicy.classify(AssetExtractor.TWITTER_IMAGE).first)
    }

    /** 约定式兜底探测出来的 /favicon.ico 不如页面自己声明的可信 */
    @Test
    fun `conventional fallback is degraded`() {
        assertEquals(
            AssetQuality.DEGRADED,
            AssetRolePolicy.classify(AssetExtractor.FAVICON_ICO_FALLBACK).second
        )
        assertEquals(
            AssetQuality.TRUSTED,
            AssetRolePolicy.classify(AssetExtractor.LINK_ICON).second
        )
    }

    /** scrapper 将来新增 extractor 时不能把整条流程打挂 */
    @Test
    fun `unknown extractor degrades instead of throwing`() {
        val (role, quality) = AssetRolePolicy.classify(AssetExtractor.UNKNOWN)
        assertEquals(AssetRole.FAVICON, role)
        assertEquals(AssetQuality.DEGRADED, quality)
    }

    // ── 降级判定 ────────────────────────────────────────────────────────────

    /**
     * 前几轮反复讨论的那个场景在数据层的落点：某站的 apple-touch-icon 与 favicon 字节
     * 完全相同，说明它压根没有独立 LOGO。
     */
    @Test
    fun `logo sharing a hash with a favicon is downgraded`() {
        val shared = "sha256:samebytes"
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 32, hash = shared),
                asset(AssetExtractor.MANIFEST_ICON, size = 32, hash = shared),
            )
        )
        val logo = assets.first { it.role == AssetRole.LOGO }
        assertEquals(
            AssetQuality.DEGRADED,
            logo.quality,
            "与 favicon 同 hash 的 LOGO 只是 favicon 换了个名字"
        )
    }

    @Test
    fun `logo with its own bytes stays trusted`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 32, hash = "sha256:favicon"),
                asset(AssetExtractor.MANIFEST_ICON, size = 512, hash = "sha256:reallogo"),
            )
        )
        assertEquals(AssetQuality.TRUSTED, assets.first { it.role == AssetRole.LOGO }.quality)
    }

    /** 完全没有 LOGO 时借一张 favicon 顶上，但必须标记为降级 */
    @Test
    fun `missing logo borrows from the icon family as degraded`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(asset(AssetExtractor.APPLE_TOUCH_ICON, size = 180, hash = "sha256:a"))
        )
        val logo = assets.firstOrNull { it.role == AssetRole.LOGO }
        assertNotNull(logo, "应借一张顶上")
        assertEquals(AssetQuality.DEGRADED, logo.quality)
    }

    @Test
    fun `primary is chosen per role by quality then size`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 16, url = "https://x/16.png"),
                asset(AssetExtractor.LINK_ICON, size = 64, url = "https://x/64.png"),
                asset(AssetExtractor.MANIFEST_ICON, size = 512, url = "https://x/512.png"),
            )
        )
        val favPrimary = assets.filter { it.role == AssetRole.FAVICON }.first { it.isPrimary }
        assertEquals(64, favPrimary.width)
        assertEquals(1, assets.count { it.role == AssetRole.FAVICON && it.isPrimary })
    }

    /** 取不到的那张要保留下来（记录"声明了但拿不到"），但不能被选为 primary */
    @Test
    fun `failed assets are kept but never primary`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 512, error = "probe failed: 404"),
                asset(AssetExtractor.APPLE_TOUCH_ICON, size = 32),
            )
        )
        assertEquals(2, assets.size)
        assertFalse(assets.first { it.errorMsg != null }.isPrimary)
    }

    // ── 按展示模式选图：两种模式优先级相反 ────────────────────────────────

    @Test
    fun `tile prefers logo while list prefers favicon`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 32, hash = "sha256:fav", url = "https://x/fav.png"),
                asset(AssetExtractor.MANIFEST_ICON, size = 512, hash = "sha256:logo", url = "https://x/logo.png"),
            )
        )
        assertEquals(
            "https://x/logo.png",
            AssetRolePolicy.resolve(assets, DisplayMode.TILE)?.resolvedUrl,
            "大图模式要品牌 LOGO"
        )
        assertEquals(
            "https://x/fav.png",
            AssetRolePolicy.resolve(assets, DisplayMode.LIST)?.resolvedUrl,
            "列表模式要 favicon —— 把 512px 塞进 16px 行里没有意义"
        )
    }

    /** 小图场景矢量图最优：任意尺寸都清晰 */
    @Test
    fun `list mode prefers a vector icon`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 64, url = "https://x/64.png"),
                asset(AssetExtractor.LINK_MASK_ICON, vector = true, url = "https://x/m.svg"),
            )
        )
        assertEquals("https://x/m.svg", AssetRolePolicy.resolve(assets, DisplayMode.LIST)?.resolvedUrl)
    }

    /** 小图取"刚好够用"：宁可略大于 64，也不要小于 64 被放大糊掉 */
    @Test
    fun `list mode picks the size closest to but not below 64`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 16, url = "https://x/16.png"),
                asset(AssetExtractor.LINK_ICON, size = 72, url = "https://x/72.png"),
                asset(AssetExtractor.LINK_ICON, size = 512, url = "https://x/512.png"),
            )
        )
        assertEquals("https://x/72.png", AssetRolePolicy.resolve(assets, DisplayMode.LIST)?.resolvedUrl)
    }

    /** 人工钉死的资产无条件胜出，覆盖一切自动规则 */
    @Test
    fun `pinned asset wins over automatic selection`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 32, url = "https://x/fav.png"),
                asset(AssetExtractor.MANIFEST_ICON, size = 512, url = "https://x/logo.png"),
            )
        )
        val pinned = assets.first { it.resolvedUrl == "https://x/fav.png" }
        assertEquals(
            "https://x/fav.png",
            AssetRolePolicy.resolve(assets, DisplayMode.TILE, pinnedAssetId = pinned.id)?.resolvedUrl
        )
    }

    @Test
    fun `resolve returns null when nothing is renderable`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(asset(AssetExtractor.LINK_ICON, error = "404"))
        )
        assertNull(AssetRolePolicy.resolve(assets, DisplayMode.TILE))
        assertNull(AssetRolePolicy.resolve(assets, DisplayMode.LIST))
    }

    // ── 首字母色块降级 ──────────────────────────────────────────────────────

    /**
     * 只有 32px 的 favicon 时，大图模式应放弃图片走首字母色块 ——
     * 拉伸到 72px 观感很差。
     */
    @Test
    fun `tile falls back to monogram when only a small favicon exists`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(asset(AssetExtractor.LINK_ICON, size = 32, hash = "sha256:a"))
        )
        val chosen = AssetRolePolicy.resolve(assets, DisplayMode.TILE)
        assertTrue(AssetRolePolicy.shouldFallbackToMonogram(chosen))
    }

    @Test
    fun `tile keeps a large trusted logo`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(asset(AssetExtractor.MANIFEST_ICON, size = 512, hash = "sha256:logo"))
        )
        val chosen = AssetRolePolicy.resolve(assets, DisplayMode.TILE)
        assertFalse(AssetRolePolicy.shouldFallbackToMonogram(chosen))
    }

    /** 矢量图没有固有像素尺寸，任何场景都够用 */
    @Test
    fun `vector logo never triggers the monogram fallback`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(asset(AssetExtractor.MANIFEST_ICON, vector = true, url = "https://x/l.svg"))
        )
        assertFalse(AssetRolePolicy.shouldFallbackToMonogram(AssetRolePolicy.resolve(assets, DisplayMode.TILE)))
    }

    @Test
    fun `no assets at all means monogram`() {
        assertTrue(AssetRolePolicy.shouldFallbackToMonogram(null))
    }

    // ── 页面封面（详情弹窗顶部那张宽图） ────────────────────────────────────

    /** 手工造一条已定 role 的资产：封面选取发生在 assignRoles 之后，不需要再过一遍映射表 */
    private fun roled(role: AssetRole, size: Int, quality: AssetQuality = AssetQuality.TRUSTED) =
        asset(AssetExtractor.OG_IMAGE, size = size, url = "https://x/${role.name.lowercase()}.png")
            .apply { this.role = role; this.quality = quality }

    /**
     * 截图是"这一页此刻真实的样子"，og:image 往往是全站共用的品牌 banner。
     * 前者信息量更大，所以排在前面。
     */
    @Test
    fun `cover prefers the screenshot over the social image`() {
        val chosen = AssetRolePolicy.resolveCover(
            listOf(roled(AssetRole.SOCIAL, 1200), roled(AssetRole.SCREENSHOT, 1280))
        )
        assertEquals(AssetRole.SCREENSHOT, assertNotNull(chosen).role)
    }

    /** 抓不到截图（反爬站点、无头熔断）是常态，og:image 是天然兜底 */
    @Test
    fun `cover falls back to the social image when there is no screenshot`() {
        val chosen = AssetRolePolicy.resolveCover(listOf(roled(AssetRole.SOCIAL, 1200)))
        assertEquals(AssetRole.SOCIAL, assertNotNull(chosen).role)
    }

    /**
     * 封面**不能**从图标里凑合。
     *
     * 这条是防回归的：一旦有人图省事把 SCREENSHOT 加进 [AssetRolePolicy.resolve] 的
     * roleOrder，或者把 FAVICON/LOGO 加进封面顺序，两个选取就会互相串味 —— 一张 1280×720
     * 的截图会因为"尺寸最大"在 TILE 模式下胜出，变成书签的图标。
     */
    @Test
    fun `cover never borrows an icon`() {
        assertNull(
            AssetRolePolicy.resolveCover(
                listOf(roled(AssetRole.LOGO, 512), roled(AssetRole.FAVICON, 64))
            ),
            "图标不是封面：宁可不渲染，也不要把一张方形 LOGO 拉成宽幅",
        )
    }

    /** 反过来同样要守住：截图不该参与图标选取 */
    @Test
    fun `icon resolution ignores screenshots entirely`() {
        val chosen = AssetRolePolicy.resolve(
            listOf(roled(AssetRole.SCREENSHOT, 1280), roled(AssetRole.FAVICON, 64)),
            DisplayMode.TILE,
        )
        assertEquals(AssetRole.FAVICON, assertNotNull(chosen).role, "TILE 也不该挑中截图")
    }

    @Test
    fun `no page assets means no cover`() {
        assertNull(AssetRolePolicy.resolveCover(emptyList()))
    }

    /** 下载失败的那条不可渲染，不能因为它排在前面就选中 */
    @Test
    fun `cover skips unrenderable assets`() {
        val broken = roled(AssetRole.SCREENSHOT, 1280).apply { errorMsg = "upload failed" }
        val chosen = AssetRolePolicy.resolveCover(listOf(broken, roled(AssetRole.SOCIAL, 1200)))
        assertEquals(AssetRole.SOCIAL, assertNotNull(chosen).role)
    }
}
