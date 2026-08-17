package top.tcyeee.bookmarkify.server.asset

import top.tcyeee.bookmarkify.entity.dto.scrape.AssetExtractor
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.entity.enums.IconVerdict
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

    /**
     * 完全没有 LOGO 时借一张 favicon 顶上，**但不改它的可信度**。
     *
     * 借用改变的是「这张图派什么用场」，不是「这张图可不可信」。从前这里会把借来的那张压成
     * DEGRADED —— `APPLE_TOUCH_ICON` 在 TABLE 里本是 (FAVICON, TRUSTED)，于是一张本来可信的
     * 大图被主动降级，再被当时 `shouldFallbackToMonogram` 的可信度否决判成色块。
     */
    @Test
    fun `missing logo borrows from the icon family without downgrading it`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(asset(AssetExtractor.APPLE_TOUCH_ICON, size = 180, hash = "sha256:a"))
        )
        val logo = assets.firstOrNull { it.role == AssetRole.LOGO }
        assertNotNull(logo, "应借一张顶上")
        assertEquals(
            AssetQuality.TRUSTED,
            logo.quality,
            "apple-touch-icon 被当 LOGO 用之后并不会变得不可信",
        )
    }

    /**
     * 借用要取同族**最大的一张**，不是列表里的第一张。
     *
     * 站点声明多张不同尺寸的 apple-touch-icon 是常态，从前的 `firstOrNull` 抓到哪张纯看
     * 列表顺序。生产实测：`live.bilibili.com` 借到 32px 而同族 512px 那张留在 FAVICON 层，
     * `www.jianshu.com` 借到 57px 而不是 152px，`tool.lu` 借到 57px 而不是 144px ——
     * 借来的小图还会因为「LOGO 层一非空就 return」挡住旁边那张大图。
     */
    @Test
    fun `borrowing takes the largest of the family, not the first`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.APPLE_TOUCH_ICON, size = 32, hash = "h32", url = "https://x/32.png"),
                asset(AssetExtractor.APPLE_TOUCH_ICON, size = 512, hash = "h512", url = "https://x/512.png"),
                asset(AssetExtractor.APPLE_TOUCH_ICON, size = 152, hash = "h152", url = "https://x/152.png"),
            )
        )
        val logo = assets.first { it.role == AssetRole.LOGO }
        assertEquals(512, logo.width, "借用应取同族最大的一张")
        // 顺带确认整条链的结果：借到大图 + 不降级 ⇒ TILE 下正常显示，而不是首字母色块
        assertFalse(AssetRolePolicy.shouldFallbackToMonogram(AssetRolePolicy.resolve(assets, DisplayMode.TILE)))
    }

    /** 借用的顺序仍按 [LOGO_FALLBACK_ORDER]：先看 apple-touch，再退 ms-tile / link-icon */
    @Test
    fun `borrowing prefers the apple touch family over a plain link icon`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 512, hash = "hlink", url = "https://x/link.png"),
                asset(AssetExtractor.APPLE_TOUCH_ICON, size = 180, hash = "happle", url = "https://x/apple.png"),
            )
        )
        assertEquals(
            "https://x/apple.png",
            assets.first { it.role == AssetRole.LOGO }.resolvedUrl,
            "出处顺序优先于尺寸：apple-touch-icon 更接近「主屏图标」这个用途",
        )
    }

    /**
     * **判定必须与入参顺序无关。** 打乱同一批资产，结果必须逐字相同。
     *
     * 这不是理论洁癖，是 2026-08-17 生产上真出过的事：首次跑存量重算，第一次改了 205 行，
     * 紧接着空跑仍报 2 行待改 —— `maxWithOrNull` 在完全同分的候选里取「第一个」，于是结果
     * 取决于入参顺序，而抓取时的顺序是 scrapper 的返回序、重算时是数据库的**堆序**
     * （一行被 UPDATE 过之后新版本元组落在堆的另一处，扫描顺序就此改变）。
     *
     * 同分是常态而非边角：一个站点声明两张矢量图标（`<link rel=icon>` 与 `<link rel=mask-icon>`
     * 各一张 svg）时，两者 `effectiveSize()` 同为 `Int.MAX_VALUE`、quality 同为 TRUSTED，
     * 一个字都分不开。下面这组用例就是照那个形态构造的。
     */
    @Test
    fun `judgement does not depend on input order`() {
        fun sample() = listOf(
            // 两张矢量图：尺寸与可信度完全同分，只有 id 分得开
            asset(AssetExtractor.LINK_ICON, vector = true, hash = "hv1", url = "https://x/a.svg"),
            asset(AssetExtractor.LINK_MASK_ICON, vector = true, hash = "hv2", url = "https://x/b.svg"),
            asset(AssetExtractor.LINK_ICON, size = 32, hash = "h32", url = "https://x/32.png"),
            // 两张同尺寸的 apple-touch：借用时同样同分
            asset(AssetExtractor.APPLE_TOUCH_ICON, size = 180, hash = "ha1", url = "https://x/a180.png"),
            asset(AssetExtractor.APPLE_TOUCH_ICON, size = 180, hash = "ha2", url = "https://x/b180.png"),
        )

        // 固定 id，否则每次 sample() 生成的随机 id 会让「顺序无关」无从比较
        fun fixture(order: List<Int>) =
            order.map { idx -> sample()[idx].copy(id = "asset-$idx") }

        val forward = AssetRolePolicy.assignRoles(fixture(listOf(0, 1, 2, 3, 4)))
        val shuffled = AssetRolePolicy.assignRoles(fixture(listOf(4, 2, 0, 3, 1)))

        fun fingerprint(rows: List<SiteAssetEntity>) =
            rows.sortedBy { it.id }.joinToString("|") { "${it.id}:${it.role}:${it.quality}:${it.isPrimary}" }

        assertEquals(fingerprint(forward), fingerprint(shuffled), "assignRoles 的结果不该取决于入参顺序")
        assertEquals(
            AssetRolePolicy.resolve(forward, DisplayMode.TILE)?.id,
            AssetRolePolicy.resolve(shuffled, DisplayMode.TILE)?.id,
            "TILE 选图不该取决于入参顺序",
        )
        assertEquals(
            AssetRolePolicy.resolve(forward, DisplayMode.LIST)?.id,
            AssetRolePolicy.resolve(shuffled, DisplayMode.LIST)?.id,
            "LIST 选图不该取决于入参顺序",
        )
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

    /**
     * **可信度不再是拒绝显示图片的理由 —— 只有尺寸是。**
     *
     * `shouldFallbackToMonogram` 曾经是 `quality == DEGRADED || size < TILE_MIN_SIZE`，
     * 把出处判断（「这不是品牌 LOGO，只是 favicon 换了个 rel」）和渲染判断（「放大会糊」）
     * 混成了一个。代价实测出来是 **40 个站点**：选中的图 ≥128px，却被渲染成首字母色块，
     * 占生产全部站点的 25%。
     *
     * 这条用例构造的正是那个形态 —— 一张 512px 的 manifest icon 与 favicon 字节相同，
     * 于是被 `assignRoles` 第二遍降级成 DEGRADED。它必须照样显示出来。
     */
    @Test
    fun `a large icon renders even when its quality is degraded`() {
        val shared = "sha256:samebytes"
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 512, hash = shared, url = "https://x/fav.png"),
                asset(AssetExtractor.MANIFEST_ICON, size = 512, hash = shared, url = "https://x/logo.png"),
            )
        )
        val chosen = AssetRolePolicy.resolve(assets, DisplayMode.TILE)
        assertNotNull(chosen)
        assertEquals(AssetQuality.DEGRADED, chosen.quality, "前提：这张图确实是被降级过的")
        assertFalse(
            AssetRolePolicy.shouldFallbackToMonogram(chosen),
            "512px 的图放在 72px 磁贴上很好看，它算不算「真 logo」与要不要显示无关",
        )
    }

    /** 反过来：可信度再高，尺寸不够照样走色块 —— 判据换成了尺寸，不是取消了判据 */
    @Test
    fun `a small icon still falls back even when trusted`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(asset(AssetExtractor.MANIFEST_ICON, size = 32, hash = "sha256:small"))
        )
        val chosen = AssetRolePolicy.resolve(assets, DisplayMode.TILE)
        assertEquals(AssetQuality.TRUSTED, chosen!!.quality)
        assertTrue(AssetRolePolicy.shouldFallbackToMonogram(chosen))
    }

    /**
     * **LOGO 优先是偏好，不是绝对闸门。** 只有一张小 LOGO、而 FAVICON 层有大图时，TILE 要取大图。
     *
     * `roleOrder` 的循环一旦在 LOGO 层拿到候选就 `return`，永不下探 FAVICON —— 于是一张 48px
     * 的 LOGO 会挡住旁边 192px 的 favicon，最终退成首字母色块。生产实测被这条挡住的有
     * `gitlab.com`（LOGO 尺寸未知 vs 两张 192px favicon）、`element.eleme.cn` / `www.chiphell.com`
     * （小 LOGO vs 矢量 mask-icon）、`www.iconfont.cn`、`wallhere.com`。
     */
    @Test
    fun `tile descends to the favicon layer when the only logo is too small`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.MANIFEST_ICON, size = 48, hash = "hlogo", url = "https://x/logo48.png"),
                asset(AssetExtractor.LINK_ICON, size = 192, hash = "hfav", url = "https://x/fav192.png"),
            )
        )
        val chosen = AssetRolePolicy.resolve(assets, DisplayMode.TILE)
        assertEquals("https://x/fav192.png", chosen?.resolvedUrl, "小 LOGO 不该挡住大 favicon")
        assertFalse(AssetRolePolicy.shouldFallbackToMonogram(chosen))
    }

    /** 同一层内部同理：可信度不能压过尺寸，TRUSTED 48px 不该胜过 DEGRADED 192px（hellogithub.com 的形态） */
    @Test
    fun `tile does not let a trusted small logo beat a degraded large one`() {
        val shared = "sha256:favbytes"
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                // 这张与 favicon 同字节，第二遍会把它降级成 DEGRADED
                asset(AssetExtractor.MANIFEST_ICON, size = 192, hash = shared, url = "https://x/big.png"),
                asset(AssetExtractor.LINK_ICON, size = 32, hash = shared, url = "https://x/fav.png"),
                asset(AssetExtractor.MANIFEST_ICON, size = 48, hash = "hsmall", url = "https://x/small.png"),
            )
        )
        val chosen = AssetRolePolicy.resolve(assets, DisplayMode.TILE)
        assertEquals("https://x/big.png", chosen?.resolvedUrl, "够大才是硬要求，可信度只是同档之间的偏好")
    }

    /** 但候选全都撑不起大图时，role 偏好继续生效 —— 反正无论选谁都会走色块 */
    @Test
    fun `role preference still applies when nothing is large enough`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.MANIFEST_ICON, size = 48, hash = "hlogo", url = "https://x/logo.png"),
                asset(AssetExtractor.LINK_ICON, size = 64, hash = "hfav", url = "https://x/fav.png"),
            )
        )
        val chosen = AssetRolePolicy.resolve(assets, DisplayMode.TILE)
        assertEquals("https://x/logo.png", chosen?.resolvedUrl)
        assertTrue(AssetRolePolicy.shouldFallbackToMonogram(chosen))
    }

    /** LIST 不受这道筛子影响：小图场景本来就该要小图，16px 的行里 512px 的 LOGO 是纯浪费 */
    @Test
    fun `list is unaffected by the tile size filter`() {
        val assets = AssetRolePolicy.assignRoles(
            listOf(
                asset(AssetExtractor.LINK_ICON, size = 64, hash = "hfav", url = "https://x/fav64.png"),
                asset(AssetExtractor.MANIFEST_ICON, size = 512, hash = "hlogo", url = "https://x/logo.png"),
            )
        )
        assertEquals("https://x/fav64.png", AssetRolePolicy.resolve(assets, DisplayMode.LIST)?.resolvedUrl)
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

    // ── 截图绝不做图标：库里 role 被写歪时也一样 ──────────────────────────────

    /** 库里的截图行，`role` 那一列可以被调用方摆成任意值（生产上就被写成了 FAVICON） */
    private fun screenshotRow(size: Int = 1280, role: AssetRole = AssetRole.SCREENSHOT) =
        SiteAssetEntity(
            pageId = "bm-1",
            ownerType = AssetOwnerType.PAGE,
            ownerId = "bm-1",
            extractor = AssetRolePolicy.SCREENSHOT_EXTRACTOR,
            originUrl = "https://x/page",
            resolvedUrl = "https://x/page",
            storageUrl = "scrapper/shot/abc.webp",
            width = size,
            height = size * 9 / 16,
        ).apply { this.role = role }

    /**
     * 上一条测试守的是"role 是对的"这个前提；这一条守的是前提不成立的时候。
     *
     * 生产实况（2026-08-18 查库）：`site_asset` 里 76 行 `extractor='HEADLESS_CAPTURE'` 的截图，
     * **role 全部是 FAVICON**、其中 71 行还是 `is_primary`。成因见 [AssetRolePolicy.assignRoles]
     * 第一遍的注释。截图归 PAGE 层，[AssetRolePolicy.resolve] 里的 `preferPageOwned` 一见到
     * PAGE 层图标就把站点图标整批筛掉 —— 所以这不是"截图和 favicon 里挑一个"，而是候选池里
     * **只剩截图**，两种模式一起中招。
     *
     * 因此读侧的判据必须是 `extractor` 这个事实，而不是 `role` 这个判定产物。
     */
    @Test
    fun `a screenshot mis-roled as favicon still never becomes the icon`() {
        for (mode in DisplayMode.entries) {
            val chosen = AssetRolePolicy.resolve(
                listOf(
                    screenshotRow(role = AssetRole.FAVICON),
                    siteIcon("aaa", role = AssetRole.FAVICON, size = 64),
                ),
                mode,
            )
            assertEquals(
                "aaa",
                assertNotNull(chosen, "$mode: 站点 favicon 才是图标").contentHash,
                "$mode: 截图被写成 FAVICON 时也不能当图标",
            )
        }
    }

    /** 一个页面只有截图时，宁可走首字母色块 —— 缩略图当图标比没有图标更糟 */
    @Test
    fun `a page with only a screenshot resolves to no icon at all`() {
        assertNull(
            AssetRolePolicy.resolve(listOf(screenshotRow(role = AssetRole.FAVICON)), DisplayMode.TILE),
            "只有截图就等于没有图标",
        )
    }

    /**
     * 写侧：重算把被写歪的截图行改回 SCREENSHOT，而不是跳过它。
     *
     * `AssetVerdictRecomputeService` 是按 owner 整组把库里的行喂回 [AssetRolePolicy.assignRoles]
     * 的，所以让它认得截图不只是"别再写坏"，也是存量那 76 行唯一的修复路径。
     */
    @Test
    fun `assign roles repairs a screenshot that was classified as an icon`() {
        val shot = screenshotRow(role = AssetRole.FAVICON).apply { quality = AssetQuality.DEGRADED }
        val favicon = asset(AssetExtractor.LINK_ICON, size = 32, hash = "f1")
        val logo = asset(AssetExtractor.MANIFEST_ICON, size = 512, hash = "l1")

        AssetRolePolicy.assignRoles(listOf(shot, favicon, logo))

        assertEquals(AssetRole.SCREENSHOT, shot.role, "截图的角色由它自己的出处决定，不查 TABLE")
        assertEquals(AssetQuality.TRUSTED, shot.quality, "我方渲染的图，出处百分之百确定")
        assertEquals(AssetRole.FAVICON, favicon.role, "同组里页面声明的图照常判定")
        assertEquals(AssetRole.LOGO, logo.role)
        assertTrue(logo.isPrimary, "截图不参与图标那两层的 primary 之争")
    }

    /** 幂等：规则没变时重跑一次不该产生任何改动，这是"空跑验证"的依据 */
    @Test
    fun `assign roles leaves a healthy screenshot untouched`() {
        val shot = screenshotRow()
        AssetRolePolicy.assignRoles(listOf(shot))
        assertEquals(AssetRole.SCREENSHOT, shot.role)
        assertEquals(AssetQuality.TRUSTED, shot.quality)
        assertTrue(shot.isPrimary, "同 role 里只有它一张，仍是 primary")
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

    // ── 后台判定总览的分档 ──────────────────────────────────────────────────

    /**
     * `tileVerdict` 必须与 `resolve` + `shouldFallbackToMonogram` **完全同步**。
     *
     * 这是分档存在的全部意义：后台那张表是用来量规则改动效果的，它一旦和线上渲染的判断漂开，
     * 就会用一个错的数字证明改动有效 —— 比没有这张表更糟。这里用穷举的方式钉死等价关系，
     * 而不是逐个断言几个样例，因为漂移恰恰发生在没被举例到的那个分支上。
     */
    @Test
    fun `tile verdict stays in lockstep with the render decision`() {
        val cases = listOf(
            "无资产" to emptyList(),
            "只有出错的图" to listOf(asset(AssetExtractor.LINK_ICON, size = 512, error = "下载失败")),
            "大图正常显示" to listOf(asset(AssetExtractor.MANIFEST_ICON, size = 512)),
            "矢量图正常显示" to listOf(asset(AssetExtractor.LINK_MASK_ICON, vector = true)),
            "小 favicon" to listOf(asset(AssetExtractor.LINK_ICON, size = 32)),
            "够大但撞 hash 被降级" to listOf(
                asset(AssetExtractor.LINK_ICON, size = 256, hash = "same"),
                asset(AssetExtractor.MANIFEST_ICON, size = 256, hash = "same"),
            ),
            "尺寸未知" to listOf(asset(AssetExtractor.JSON_LD_ORG_LOGO)),
        )

        cases.forEach { (name, raw) ->
            val assets = AssetRolePolicy.assignRoles(raw.map { it.copy() })
            val (verdict, chosen) = AssetRolePolicy.tileVerdict(assets)

            assertEquals(AssetRolePolicy.resolve(assets, DisplayMode.TILE), chosen, "$name: 选中的图应与 resolve 一致")
            assertEquals(
                AssetRolePolicy.shouldFallbackToMonogram(chosen),
                verdict != IconVerdict.IMAGE,
                "$name: IMAGE 档必须恰好等价于「不走首字母色块」",
            )
            // 两个色块档的分界线只有尺寸：MONOGRAM_QUALITY 的定义是「够大却仍然退回色块」，
            // 即尺寸之外还有别的否决权。移除 quality 否决后它恒为 0，见 IconVerdict 的注释
            if (verdict == IconVerdict.MONOGRAM_QUALITY) {
                assertTrue(chosen!!.effectiveSize() >= AssetRolePolicy.TILE_MIN_SIZE, "$name: 这一档的图必须够大")
            }
            if (verdict == IconVerdict.MONOGRAM_SIZE) {
                assertTrue(chosen!!.effectiveSize() < AssetRolePolicy.TILE_MIN_SIZE, "$name: 这一档的图必须偏小")
            }
            if (verdict == IconVerdict.NO_ASSET) assertNull(chosen, "$name: 没有结论就不该有选中的图")
        }
    }

    /**
     * `qualifiesForTile` 是 `shouldFallbackToMonogram` 的逐张版本，两者判据必须互为镜像。
     *
     * 它算的是后台那个「改进空间」数字（基线 31）：判成色块、可库里本来就躺着一张能用的图。
     * 判据一旦和渲染侧对不上，这个数就既不是改进空间也不是别的什么。
     */
    @Test
    fun `qualifiesForTile mirrors the render decision for a single asset`() {
        listOf(
            asset(AssetExtractor.MANIFEST_ICON, size = 512),
            asset(AssetExtractor.LINK_MASK_ICON, vector = true),
            asset(AssetExtractor.LINK_ICON, size = 32),
            asset(AssetExtractor.JSON_LD_ORG_LOGO),
            asset(AssetExtractor.MS_TILE_IMAGE, size = 256),
        ).forEach { raw ->
            // 单张也要先过 assignRoles：quality 是它定的，跳过就等于在测一个不存在的状态
            val one = AssetRolePolicy.assignRoles(listOf(raw)).single()
            assertEquals(
                !AssetRolePolicy.shouldFallbackToMonogram(one),
                AssetRolePolicy.qualifiesForTile(one),
                "${raw.extractor}: 逐张判据与渲染判据不一致",
            )
        }
    }

    /** 出错/未落地的资产永远不算合格候选，否则「改进空间」里会混进一批根本渲染不出来的图 */
    @Test
    fun `unrenderable assets never qualify`() {
        val broken = AssetRolePolicy.assignRoles(
            listOf(asset(AssetExtractor.MANIFEST_ICON, size = 512, error = "下载失败"))
        ).single()
        assertFalse(AssetRolePolicy.qualifiesForTile(broken))
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
