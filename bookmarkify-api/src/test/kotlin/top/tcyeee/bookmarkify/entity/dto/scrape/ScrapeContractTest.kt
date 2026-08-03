package top.tcyeee.bookmarkify.entity.dto.scrape

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * scrapper 契约的跨语言校验。
 *
 * 读的是 `contract/scrape-response.sample.json` —— Rust 侧
 * `contract.rs::shared_fixture_round_trips` 读的是**同一个文件**。任何一侧改了字段名
 * 或枚举取值，两边的测试会同时变红，避免契约悄悄漂移。
 */
class ScrapeContractTest {

    /** 与 Spring 侧一致：容忍未知枚举取值，回落到 @JsonEnumDefaultValue 标注的 UNKNOWN */
    private val mapper = jacksonObjectMapper()
        .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)

    /** Gradle 的测试工作目录是项目目录(bookmarkify-api)，仓库根在上一级 */
    private val fixture: File = sequenceOf(
        File("../contract/scrape-response.sample.json"),
        File("contract/scrape-response.sample.json"),
    ).firstOrNull { it.exists() } ?: error("找不到契约样例 contract/scrape-response.sample.json")

    private val response: ScrapeResponse by lazy { mapper.readValue(fixture.readText()) }

    @Test
    fun `fixture deserializes with transport facts intact`() {
        assertEquals("https://github.com/vbenjs/vue-vben-admin", response.fetch.finalUrl)
        assertEquals(200, response.fetch.httpStatus)
        assertEquals(RenderLayer.HTTP, response.fetch.layerUsed)
        assertEquals(1, response.fetch.redirectChain.size)
        assertEquals(301, response.fetch.redirectChain[0].status)
        assertEquals(421, response.fetch.timingMs.total)
        assertEquals(true, response.fetch.tls?.valid)
    }

    /**
     * 契约的核心：出处下沉到字段级。
     *
     * 样例里 title 来自 OG、description 却回落到了 meta[name=description]。旧契约的
     * 单一 source 字段会把这两者压扁成一个 "og"，等于在说谎。
     */
    @Test
    fun `meta sources are recorded per field, not globally`() {
        val meta = assertNotNull(response.meta)
        assertEquals(MetaExtractor.OG, meta.sources["title"]?.extractor)
        assertEquals("og:title", meta.sources["title"]?.rawKey)
        assertEquals(MetaExtractor.META_NAME, meta.sources["description"]?.extractor)
        assertEquals(MetaExtractor.MANIFEST, meta.sources["shortName"]?.extractor)
        assertEquals(MetaExtractor.HTML_ATTR, meta.sources["lang"]?.extractor)
    }

    /**
     * 截图是独立于 `assets[]` 的一路产物：不是页面声明的图，没有 extractor，也不走
     * `assets.download`。
     *
     * 这条用例存在的理由：顶层曾经根本没有 `screenshot` 对象，于是 `Screenshot` 的字段名
     * 在三处契约（Rust / Kotlin / TS）里各写各的也不会有任何测试变红 —— 而这正是截图链路
     * 长期没人发现坏掉的原因之一。
     */
    @Test
    fun `screenshot is a separate product with its own wire shape`() {
        val raw = mapper.readTree(fixture.readText())
        val node = assertNotNull(raw.get("screenshot"), "样例应含顶层 screenshot")
        assertNull(node.get("extractor"), "截图不是页面声明的图，不该有 extractor")
        assertTrue(node.has("storageKey"), "字段名必须是 camelCase 的 storageKey")

        val shot = assertNotNull(response.screenshot)
        assertEquals(ImageFormat.WEBP, shot.format)
        assertEquals(1280, shot.width)
        assertEquals(720, shot.height)
        assertTrue(
            shot.storageKey?.startsWith("http") == false,
            "storageKey 是裸 object key —— 桶私有读，签名与缩放都是 API 侧的策略",
        )
        assertNull(shot.dataUrl, "落了存储就不该再内联一份 base64")
        // 截图不混进 assets[]
        assertEquals(6, response.assets.size)
    }

    /** 两种展示模式各自需要的文案，manifest 早就分好了 name / short_name */
    @Test
    fun `site name and short name are separate fields`() {
        val meta = assertNotNull(response.meta)
        assertEquals("GitHub", meta.siteName)
        assertEquals("GitHub", meta.shortName)
        assertEquals("GitHub - vbenjs/vue-vben-admin", meta.title)
    }

    /** scrapper 只报出处，不报用途；assets 里不应存在任何 role 字段 */
    @Test
    fun `assets carry extractor as fact, never a role judgement`() {
        val raw = mapper.readTree(fixture.readText())
        raw.path("assets").forEach { node ->
            assertTrue(node.has("extractor"), "每张图都必须带 extractor")
            assertNull(node.get("role"), "role 是调用方的策略，不该出现在 scrapper 响应里")
        }
        assertEquals(6, response.assets.size)
    }

    /**
     * 同 contentHash 的 LINK_ICON 与 APPLE_TOUCH_ICON = 该站没有独立 LOGO。
     * 调用方据此让大图模式走首字母色块，而不是把 32px 的 favicon 拉伸到 72px。
     */
    @Test
    fun `identical content hash exposes a site without a real logo`() {
        val linkIcon = response.assets.first { it.extractor == AssetExtractor.LINK_ICON }
        val appleIcon = response.assets.first { it.extractor == AssetExtractor.APPLE_TOUCH_ICON }
        assertNotNull(linkIcon.contentHash)
        assertEquals(linkIcon.contentHash, appleIcon.contentHash)

        val manifestIcon = response.assets.first { it.extractor == AssetExtractor.MANIFEST_ICON }
        assertTrue(manifestIcon.contentHash != linkIcon.contentHash)
        assertEquals(512, manifestIcon.width)
        assertEquals("maskable", manifestIcon.declared.purpose)
    }

    /** 单张图失败不影响整体成功，错误落在该项自己的 error 上 */
    @Test
    fun `per asset failure is isolated`() {
        val failed = response.assets.first { it.extractor == AssetExtractor.FAVICON_ICO_FALLBACK }
        assertNotNull(failed.error)
        assertNull(failed.width)
        assertNull(failed.declared.rel, "约定式兜底来源没有任何声明属性")
        assertEquals(1, response.diagnostics.warnings.size)
    }

    /** 原始块原样透传，不做筛选或合并 */
    @Test
    fun `raw blocks pass through untouched`() {
        assertEquals(1, response.jsonld.size)
        assertEquals("SoftwareSourceCode", response.jsonld[0].path("@type").asText())
        assertEquals("GitHub", response.opengraph["site_name"])
        assertEquals("summary_large_image", response.twitter["card"])
        assertEquals("GitHub", assertNotNull(response.manifest).raw?.path("short_name")?.asText())
    }

    /** 未知枚举取值回落 UNKNOWN，而不是让整个响应解析失败 */
    @Test
    fun `unknown enum value degrades instead of failing the whole response`() {
        val mutated = fixture.readText().replace("\"LINK_MASK_ICON\"", "\"SOME_FUTURE_EXTRACTOR\"")
        val parsed: ScrapeResponse = mapper.readValue(mutated)
        assertTrue(parsed.assets.any { it.extractor == AssetExtractor.UNKNOWN })
        assertEquals(6, parsed.assets.size)
    }

    /** 默认请求体与 Rust 侧 Default 实现一致，且只用 camelCase / SCREAMING_SNAKE_CASE */
    @Test
    fun `default request matches the rust side defaults`() {
        val json = mapper.readTree(mapper.writeValueAsString(ScrapeRequest(url = "https://example.com")))
        assertEquals("AUTO", json.path("render").path("mode").asText())
        assertEquals("LOAD", json.path("render").path("waitUntil").asText())
        assertEquals("PROBE", json.path("assets").path("download").asText())
        assertEquals(2097152, json.path("assets").path("maxBytes").asLong())
        assertEquals("DEFAULT", json.path("cache").path("mode").asText())
        assertTrue(json.path("extract").path("manifest").asBoolean())
        assertTrue(json.path("robots").path("respect").asBoolean())
        assertTrue(json.path("screenshot").path("enabled").isBoolean)

        // Rust 侧请求体启用了 deny_unknown_fields：多发字段会被整体拒绝
        assertNull(json.get("headless"), "旧的 headless 字段已被 render.mode 取代")
        assertTrue(json.path("render").path("timeoutMs").isMissingNode, "null 不应出现在载荷里")
    }

    /** 管理后台"重试"必须绕过缓存，否则等于没试 */
    @Test
    fun `retry bypasses cache`() {
        val req = ScrapeRequest(
            url = "https://example.com",
            cache = CacheOptions(mode = CacheMode.BYPASS),
        )
        val json = mapper.readTree(mapper.writeValueAsString(req))
        assertEquals("BYPASS", json.path("cache").path("mode").asText())
    }
}
