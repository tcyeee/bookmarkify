package top.tcyeee.bookmarkify.server.asset

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import top.tcyeee.bookmarkify.entity.dto.scrape.ImageFormat
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.scrape.Screenshot
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 摄取路径的端到端验证：拿**跨语言契约样例**当输入，一路走到可落库的行。
 *
 * 用的是 `contract/scrape-response.sample.json` —— 同一个文件被 Rust 侧 contract.rs、
 * Kotlin 侧 ScrapeContractTest 和这里三处共读，任何一端改了契约都会同时变红。
 */
class SiteAssetIngestorTest {

    private val mapper = jacksonObjectMapper()
        .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)

    private val fixture: File = sequenceOf(
        File("../contract/scrape-response.sample.json"),
        File("contract/scrape-response.sample.json"),
    ).firstOrNull { it.exists() } ?: error("找不到契约样例 contract/scrape-response.sample.json")

    private val response: ScrapeResponse by lazy { mapper.readValue(fixture.readText()) }

    private fun project() =
        SiteAssetIngestor.project("bm-1", "https://github.com/vbenjs/vue-vben-admin", response, 421, mapper)

    @Test
    fun `snapshot keeps the whole response for later backfill`() {
        val p = project()
        assertTrue(p.snapshot.ok)
        assertEquals("bm-1", p.snapshot.bookmarkId)
        assertEquals(421, p.snapshot.durationMs)

        // 快照必须是可解析的完整响应 —— 将来想启用当时没提列的字段能直接回填
        val reparsed: ScrapeResponse = mapper.readValue(assertNotNull(p.snapshot.response))
        assertEquals(response.assets.size, reparsed.assets.size)
        assertEquals("GitHub", reparsed.meta?.siteName)
        assertNotNull(p.snapshot.request, "请求参数也要留档，排障时不必猜用了什么参数")
    }

    /** shortName 此前被解析后丢弃，现在必须落库 —— 它是大图模式短文案的唯一来源 */
    @Test
    fun `page meta captures short name and per-field sources`() {
        val meta = assertNotNull(project().pageMeta)
        assertEquals("GitHub - vbenjs/vue-vben-admin", meta.title)
        assertEquals("GitHub", meta.siteName)
        assertEquals("GitHub", meta.siteShortName)
        assertEquals("HTTP", meta.fetchLayer)
        assertEquals(200, meta.httpStatus)
        assertEquals(false, meta.antiCrawler)

        val sources = assertNotNull(meta.metaSources)
        assertTrue(sources.contains("META_NAME"), "description 的出处应如实记为 META_NAME")
        assertTrue(sources.contains("MANIFEST"), "shortName 的出处应如实记为 MANIFEST")
    }

    /**
     * 样例里 LINK_ICON 与 APPLE_TOUCH_ICON 的 contentHash 相同 —— 该站没有独立 LOGO。
     * 摄取后应当体现为：借来的 LOGO 被标成 DEGRADED，且大图模式走首字母色块。
     */
    @Test
    fun `shared hash across extractors surfaces a site without a real logo`() {
        val assets = project().assets

        val linkIcon = assets.first { it.extractor == "LINK_ICON" }
        val appleIcon = assets.first { it.extractor == "APPLE_TOUCH_ICON" }
        assertEquals(linkIcon.contentHash, appleIcon.contentHash)

        // MANIFEST_ICON 有自己的字节，仍是可信 LOGO
        val manifestIcon = assets.first { it.extractor == "MANIFEST_ICON" }
        assertEquals(AssetRole.LOGO, manifestIcon.role)
        assertEquals(AssetQuality.TRUSTED, manifestIcon.quality)
        assertEquals(512, manifestIcon.width)
        // 落库的是 scrapper 返回的 object key，不是完整 URL —— 域名与签名归 API 所有，
        // 由 OssUtils.signAsset 在展示时才拼上（见 docs/oss-architecture.md）
        assertEquals(
            "scrapper/asset/1122334455667788990011223344556677889900112233445566778899001122.png",
            manifestIcon.storageUrl,
        )
    }

    @Test
    fun `social image finally has a place to live`() {
        val social = project().assets.filter { it.role == AssetRole.SOCIAL }
        assertEquals(1, social.size, "og:image 应落成一条 SOCIAL 资产")
        assertEquals(1200, social[0].width)
        assertEquals(600, social[0].height)
        // 旧实现把社交图传上 OSS 后直接丢弃了地址，这里必须留得住
        assertTrue(social[0].resolvedUrl.isNotBlank())
    }

    /** 声明了但取不到的那张要保留，errorMsg 记录原因 */
    @Test
    fun `failed asset declaration is preserved with its error`() {
        val failed = project().assets.first { it.extractor == "FAVICON_ICO_FALLBACK" }
        assertEquals("probe failed: 404", failed.errorMsg)
        assertTrue(!failed.isPrimary)
        assertTrue(!failed.renderable())
    }

    @Test
    fun `every asset gets a role and a quality`() {
        val assets = project().assets
        assertEquals(6, assets.size, "样例里 6 条声明应全部落库")
        assertTrue(assets.all { it.bookmarkId == "bm-1" })
        assertTrue(assets.all { it.resolvedUrl.isNotBlank() })
        // 每个 role 至多一个 primary
        AssetRole.entries.forEach { role ->
            assertTrue(assets.count { it.role == role && it.isPrimary } <= 1, "$role 的 primary 不应多于一个")
        }
    }

    /** 摄取产物直接喂给选取策略，两种模式各取所需 */
    @Test
    fun `projection feeds straight into per-mode resolution`() {
        val assets = project().assets
        val tile = AssetRolePolicy.resolve(assets, DisplayMode.TILE)
        val list = AssetRolePolicy.resolve(assets, DisplayMode.LIST)
        assertNotNull(tile)
        assertNotNull(list)
        assertEquals(AssetRole.LOGO, tile.role, "大图取 LOGO")
        assertEquals(AssetRole.FAVICON, list.role, "列表取 FAVICON")
        // 该站的 manifest icon 是 512px 可信 LOGO，不该退化成首字母色块
        assertTrue(!AssetRolePolicy.shouldFallbackToMonogram(tile))
    }

    /**
     * 截图那条 SCREENSHOT 行的地址语义：`storageUrl` 是 object key，而 origin/resolved 记的是
     * **被截图的页面**。这两列以前填的也是 object key，既不是 URL，也把"这张图哪来的"弄丢了。
     *
     * 契约样例里没有 screenshot（默认不开启截图），所以这里单独造一个响应。
     */
    @Test
    fun `screenshot row points at the captured page, not at its own object key`() {
        val withShot = response.copy(
            screenshot = Screenshot(
                storageKey = "scrapper/screenshots/abc123.webp",
                width = 1280,
                height = 720,
                format = ImageFormat.WEBP,
                byteSize = 40_960,
            )
        )
        val shot = SiteAssetIngestor
            .project("bm-3", "https://github.com/vbenjs/vue-vben-admin", withShot, 900, mapper)
            .assets.single { it.role == AssetRole.SCREENSHOT }

        assertEquals("scrapper/screenshots/abc123.webp", shot.storageUrl)
        // finalUrl 是跟完重定向后的页面，截图针对的就是它
        assertEquals("https://github.com/vbenjs/vue-vben-admin", shot.resolvedUrl)
        assertEquals("https://github.com/vbenjs/vue-vben-admin", shot.originUrl)
        assertEquals(AssetQuality.TRUSTED, shot.quality)
        assertEquals("image/webp", shot.mime)
        assertEquals(1280, shot.width)
    }

    /** 只内联、没落存储的截图不该入库：base64 撑爆 site_asset，且没法参与签名/缩放 */
    @Test
    fun `inline-only screenshot is not persisted`() {
        val withInlineShot = response.copy(
            screenshot = Screenshot(dataUrl = "data:image/webp;base64,AAAA", width = 800, height = 600)
        )
        val assets = SiteAssetIngestor
            .project("bm-4", "https://github.com/vbenjs/vue-vben-admin", withInlineShot, 900, mapper)
            .assets

        assertTrue(assets.none { it.role == AssetRole.SCREENSHOT })
    }

    @Test
    fun `failure projection records only a snapshot`() {
        val p = SiteAssetIngestor.projectFailure("bm-2", "https://dead.example", "timeout", 15000)
        assertTrue(!p.snapshot.ok)
        assertEquals("timeout", p.snapshot.errorMsg)
        assertEquals(15000, p.snapshot.durationMs)
        assertEquals(null, p.pageMeta)
        assertTrue(p.assets.isEmpty())
    }
}
