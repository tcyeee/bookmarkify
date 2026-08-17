package top.tcyeee.bookmarkify.server.asset

import top.tcyeee.bookmarkify.entity.dto.scrape.AssetExtractor
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import java.time.LocalDateTime
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

    /**
     * 每个 `AssetExtractor` 取值都必须在 `AssetRolePolicy.TABLE` 里有一条映射。
     *
     * 这条纪律此前只写在根 `CLAUDE.md` 的注释里（"新增 extractor 取值时记得给 TABLE 补一条
     * 映射，否则那张图会被丢掉"）。靠人记是不够的：`classify` 对未知取值有兜底
     * （FAVICON + DEGRADED），所以**漏配不会报任何错**，只会让那张图悄悄降级成一张
     * 谁也不会选中的低质量 favicon —— 症状是"新加的站点适配器抓到了封面，但前台没有"，
     * 而日志里一切正常。
     *
     * `UNKNOWN` 是契约里 `@JsonEnumDefaultValue` 的兜底值（对端发来我方还不认识的取值时落到
     * 这里），它本来就该走 `classify` 的默认分支，所以排除在外。
     */
    @Test
    fun `every extractor has an explicit role mapping`() {
        val unmapped = AssetExtractor.entries
            .filter { it != AssetExtractor.UNKNOWN }
            .filter { AssetRolePolicy.classify(it) == (AssetRole.FAVICON to AssetQuality.DEGRADED) }
            // FAVICON_ICO_FALLBACK / MS_TILE_IMAGE 本来就显式映射成这个组合，不是漏配
            .filterNot { it in EXPLICITLY_DEGRADED_FAVICONS }
        assertTrue(
            unmapped.isEmpty(),
            "以下 extractor 没有在 AssetRolePolicy.TABLE 里配置映射，它们抓到的图会被静默降级并丢弃: $unmapped",
        )
    }

    private fun asset(
        extractor: AssetExtractor,
        size: Int? = null,
        hash: String? = null,
        vector: Boolean = false,
        url: String = "https://cdn.example.com/${extractor.name.lowercase()}.png",
        error: String? = null,
    ) = SiteAssetEntity(
        pageId = "bm-1",
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

    // ── 同域多产品：页面自带图标 ────────────────────────────────────────────

    /** 站点图标，带哈希 */
    private fun siteIcon(hash: String, role: AssetRole = AssetRole.FAVICON, size: Int = 64) =
        asset(AssetExtractor.LINK_ICON, size = size, hash = hash, url = "https://x/site-$hash.png")
            .apply { this.role = role; ownerType = AssetOwnerType.SITE; ownerId = "site-1" }

    /** 本次从某个深链投影出来的图标（此刻还没定归属） */
    private fun pageIcon(hash: String, role: AssetRole = AssetRole.FAVICON, size: Int = 64) =
        asset(AssetExtractor.LINK_ICON, size = size, hash = hash, url = "https://x/page-$hash.png")
            .apply { this.role = role }

    /**
     * `tools.example.com/tools/a` 与 `/tools/b` 是两个独立产品，各自声明了不同的图标 ——
     * 字节毫无交集就是它们唯一可靠的信号（manifest `scope` 要靠站点正确声明，靠不住）。
     */
    @Test
    fun `a page whose icons share no bytes with the site is treated as its own product`() {
        assertTrue(
            AssetRolePolicy.divergesFromSite(
                siteIcons = listOf(siteIcon("aaa")),
                pageIcons = listOf(pageIcon("bbb")),
            )
        )
    }

    /**
     * 部分重叠是普通站点的常态（共用 favicon，深链另外多声明一张 apple-touch-icon）。
     * 判据必须是**毫无交集** —— 按"有一张不同就算独立产品"去判，几乎每条深链都会误判，
     * 那等于把站点层取消掉。
     */
    @Test
    fun `sharing even one icon with the site means it is the same product`() {
        assertFalse(
            AssetRolePolicy.divergesFromSite(
                siteIcons = listOf(siteIcon("aaa")),
                pageIcons = listOf(pageIcon("aaa"), pageIcon("ccc")),
            )
        )
    }

    /**
     * 两侧都必须有可比的哈希才敢下结论。`assets.download` 非 PROBE、或那张图取回失败时
     * `contentHash` 为空 —— 此时无从比较，宁可退回"图标全站共享"这个常态。
     */
    @Test
    fun `divergence is never claimed without hashes on both sides`() {
        assertFalse(
            AssetRolePolicy.divergesFromSite(
                siteIcons = listOf(siteIcon("aaa")),
                pageIcons = listOf(pageIcon(hash = "").apply { contentHash = null }),
            ),
            "页面侧没有哈希时不能判成独立产品",
        )
        assertFalse(
            AssetRolePolicy.divergesFromSite(
                siteIcons = emptyList(),
                pageIcons = listOf(pageIcon("bbb")),
            ),
            "站点侧一张图都没有时不能判成独立产品——那是「首页还没抓过」，该走补齐",
        )
    }

    /** 取不回来的那张没有参考价值，不能靠它凑出"毫无交集" */
    @Test
    fun `unrenderable icons do not participate in the divergence check`() {
        assertFalse(
            AssetRolePolicy.divergesFromSite(
                siteIcons = listOf(siteIcon("aaa")),
                pageIcons = listOf(
                    pageIcon("aaa"),
                    pageIcon("zzz").apply { errorMsg = "download failed" },
                ),
            ),
            "唯一不同的那张下载失败了，剩下的仍与站点相同",
        )
    }

    /**
     * 页面自带图标存在时必须**压过**站点图标，而不是与之竞争。
     *
     * 站点图标往往又大又 TRUSTED，混在一起按尺寸排序会把页面自己那张挤掉 ——
     * 整条链路就白做了。
     */
    @Test
    fun `page-owned icons beat site-owned ones regardless of size`() {
        val chosen = AssetRolePolicy.resolve(
            listOf(
                siteIcon("aaa", size = 512),
                pageIcon("bbb", size = 64).apply { ownerType = AssetOwnerType.PAGE },
            ),
            DisplayMode.TILE,
        )
        assertEquals("bbb", assertNotNull(chosen).contentHash)
    }

    /** 绝大多数站点没有页面级图标，此时行为必须与改造前完全一致 */
    @Test
    fun `site icons are still used when the page has none of its own`() {
        val chosen = AssetRolePolicy.resolve(
            listOf(siteIcon("aaa", size = 512), siteIcon("ccc", size = 64)),
            DisplayMode.TILE,
        )
        assertEquals("aaa", assertNotNull(chosen).contentHash, "同为站点图标时仍按尺寸选")
    }

    /**
     * 页面往往**只拥有一个 role**，另一个 role 绝不能悄悄回落到站点图标。
     *
     * 这正是发散页面的常态：一个只声明了单个 `<link rel=icon>` 的深链，那张图会被
     * [AssetRolePolicy.assignRoles] 的第三遍借用逻辑改写成 LOGO，于是它根本没有 PAGE 层的
     * FAVICON。若 preferPageOwned 按 role 分别生效，LIST 模式先找 FAVICON、页面侧为空，
     * 就会挑中站点 favicon —— 渲染出隔壁产品的图标，整条链路的目的落空。
     */
    @Test
    fun `a page owning only a LOGO still never falls back to the site favicon`() {
        val chosen = AssetRolePolicy.resolve(
            listOf(
                siteIcon("aaa", role = AssetRole.FAVICON, size = 64),
                pageIcon("bbb", role = AssetRole.LOGO, size = 256)
                    .apply { ownerType = AssetOwnerType.PAGE },
            ),
            // LIST 的 roleOrder 是 FAVICON→LOGO，页面侧恰好缺的就是排在前面的那个
            DisplayMode.LIST,
        )
        assertEquals("bbb", assertNotNull(chosen).contentHash, "宁可用偏大的页面 LOGO，也不用隔壁产品的 favicon")
    }

    /** 反方向同理：页面只有 FAVICON 时，TILE 也不该退回站点的 LOGO */
    @Test
    fun `a page owning only a FAVICON still never falls back to the site logo`() {
        val chosen = AssetRolePolicy.resolve(
            listOf(
                siteIcon("aaa", role = AssetRole.LOGO, size = 512),
                pageIcon("bbb", role = AssetRole.FAVICON, size = 64)
                    .apply { ownerType = AssetOwnerType.PAGE },
            ),
            DisplayMode.TILE,
        )
        assertEquals("bbb", assertNotNull(chosen).contentHash)
    }

    /**
     * 社交图不能把图标池饿死。
     *
     * SOCIAL/SCREENSHOT 按 [AssetRolePolicy.ownerTypeOf] **恒为** PAGE 层，所以"页面自有资产
     * 优先"这条筛选必须先收敛到图标角色再生效。否则任何一张 og:image 都会让候选集缩成
     * "只有社交图"，图标一张不剩，[AssetRolePolicy.resolve] 直接返回 null —— 所有带社交图的
     * 普通书签会集体退成首字母色块。
     */
    @Test
    fun `a page-owned social image does not starve the icon pool`() {
        val chosen = AssetRolePolicy.resolve(
            listOf(
                siteIcon("aaa", role = AssetRole.FAVICON, size = 64),
                roled(AssetRole.SOCIAL, 1200).apply { ownerType = AssetOwnerType.PAGE },
            ),
            DisplayMode.LIST,
        )
        assertEquals("aaa", assertNotNull(chosen).contentHash, "社交图不参与图标选取，站点 favicon 照常胜出")
    }

    /**
     * 站点图标太旧时不敢下发散结论。
     *
     * 「零交集」有两个解释：这一页是另一个产品，或者**站点换了图标而库里那份是旧的**。
     * 深链抓取从不刷新 SITE 行，所以一次改版之后该域名下每一条深链都与陈旧的 SITE 行零交集；
     * 若照判，整站图标会被逐页打散——正是站点层要消除的重复。
     */
    @Test
    fun `stale site icons cannot establish divergence`() {
        val now = LocalDateTime.of(2026, 8, 4, 12, 0)
        assertFalse(
            AssetRolePolicy.divergesFromSite(
                siteIcons = listOf(siteIcon("aaa").apply { fetchedAt = now.minusDays(120) }),
                pageIcons = listOf(pageIcon("bbb")),
                now = now,
            ),
            "站点图标已 120 天没验证过，零交集更可能是站点换了图标",
        )
    }

    /** 反向守卫：新鲜的站点图标照常能判出发散，别把闸门收得连正常情况都过不去 */
    @Test
    fun `fresh site icons still establish divergence`() {
        val now = LocalDateTime.of(2026, 8, 4, 12, 0)
        assertTrue(
            AssetRolePolicy.divergesFromSite(
                siteIcons = listOf(siteIcon("aaa").apply { fetchedAt = now.minusDays(3) }),
                pageIcons = listOf(pageIcon("bbb")),
                now = now,
            )
        )
    }

    /** 只要图标集里有**任意一张**是近期抓到的，这份快照就还能代表"站点现在长什么样" */
    @Test
    fun `one freshly fetched site icon is enough to trust the whole set`() {
        val now = LocalDateTime.of(2026, 8, 4, 12, 0)
        assertTrue(
            AssetRolePolicy.divergesFromSite(
                siteIcons = listOf(
                    siteIcon("aaa").apply { fetchedAt = now.minusDays(300) },
                    siteIcon("ccc").apply { fetchedAt = now.minusDays(2) },
                ),
                pageIcons = listOf(pageIcon("bbb")),
                now = now,
            )
        )
    }

    companion object {
        /**
         * 这几个取值**确实**被显式映射成了 `FAVICON + DEGRADED`，与"没配映射时的兜底值"
         * 恰好相同，因此穷尽性检查必须把它们排除，否则会误报。
         * 新增一条真正映射到该组合的 extractor 时，记得同步加进来。
         */
        private val EXPLICITLY_DEGRADED_FAVICONS = setOf(
            AssetExtractor.FAVICON_ICO_FALLBACK,
            AssetExtractor.MS_TILE_IMAGE,
        )
    }
}
