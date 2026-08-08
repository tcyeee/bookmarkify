package top.tcyeee.bookmarkify.utils

import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.entity.enums.OssAddressing
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「这条资产地址能不能被缓存住」的两条判据。
 *
 * 它们看着像性能调优，实际管的是钱：签名 URL 每滚动一个窗口就变一次，URL 一变浏览器缓存
 * 即失效，于是**每个用户每次打开桌面**都要为屏幕上每张图重付一次 OSS GET 请求费和一次
 * 图片处理费。成本随「用户数 × 打开次数 × 书签数 × 尺寸档数」线性增长，四项里没有一项
 * 跟收入挂钩 —— 单用户实测每天能烧掉五毛，而单用户日均收入远到不了这个数。
 *
 * 判错的两个方向代价同样不对称：
 * - **该长的判短** → 只是白花钱，功能正常，因而**没有任何症状**，能烧很久没人发现。
 * - **该短的判长** → 一个会被原地覆盖的 key 拿到 `immutable` 承诺，改动被浏览器钉死，
 *   且没有任何办法让已发出去的 URL 失效。
 *
 * 所以下面每条用例都在钉死"哪些对象属于哪一侧"。
 */
class OssCacheableUrlTest {

    private fun ledgerRow(addressing: OssAddressing) =
        OssObjectEntity(objectKey = "scrapper/asset/abc123", addressing = addressing)

    @BeforeTest
    fun setUp() {
        OssUtils.initDomain("https://oss-cn-hangzhou.aliyuncs.com", "", "test-bucket")
        OssUtils.initProxyDomain("")
    }

    @AfterTest
    fun tearDown() = OssUtils.initProxyDomain("")

    // ---------------------------------------------------------------- immutable

    @Test
    fun `content addressed objects never change`() {
        assertTrue(ledgerRow(OssAddressing.CONTENT).immutable)
    }

    /**
     * 这条是本次修复的主角。`RANDOM` 曾被判成 false，理由想当然：key 不是从字节推导的。
     * 但可变性问的是"这个 key 会不会被**原地覆盖写**"，而不是"key 怎么来的" —— 用户换一次
     * 头像就是一个新 UUID、一个新 key，旧 key 那份字节永远不会再变。
     *
     * 判错的代价全落在头像和背景图上：它们每小时换一次 URL、每小时全量回源一次。
     */
    @Test
    fun `random uuid objects are write-once too`() {
        assertTrue(
            ledgerRow(OssAddressing.RANDOM).immutable,
            "换头像产生的是新 key，旧 key 的字节不会被覆盖",
        )
    }

    /**
     * 截图是唯一真正会自我覆盖的一类（按源 URL 寻址，为的是让存储量有上界），
     * 必须留在短有效期那一侧。LEGACY 的推导方式不可考，同样不能假设它不变。
     */
    @Test
    fun `self-overwriting and unknown addressing stay mutable`() {
        assertFalse(ledgerRow(OssAddressing.SOURCE_URL).immutable, "截图会被补抓原地覆盖")
        assertFalse(ledgerRow(OssAddressing.LEGACY).immutable, "推导方式不可考，不能假设不变")
    }

    // ------------------------------------------------------------- 缓存代理出口

    @Test
    fun `proxy stays off until a domain is configured`() {
        val url = OssUtils.signAsset("scrapper/asset/abc123", 64, immutable = true, mime = "image/png")
        assertFalse(url.orEmpty().contains("/oss/"), "未配置代理域名时不应改变既有行为")
    }

    @Test
    fun `immutable objects route through the proxy without a signature`() {
        OssUtils.initProxyDomain("https://cdn.bookmarkify.cc")

        val url = OssUtils.signAsset("scrapper/asset/abc123", 64, immutable = true, mime = "image/png")

        assertEquals("https://cdn.bookmarkify.cc/oss/scrapper/asset/abc123?w=64&h=64", url)
    }

    /**
     * 代理地址的**全部价值**在于它逐字节恒定 —— 只要还带签名，浏览器就还是每个窗口回源一次，
     * 这套东西等于白做。所以这里直接钉死"两次调用结果相同"和"不含签名参数"。
     */
    @Test
    fun `the proxy url is byte-identical across calls`() {
        OssUtils.initProxyDomain("https://cdn.bookmarkify.cc")

        val first = OssUtils.signAsset("scrapper/asset/abc123", 256, immutable = true, mime = "image/png")
        val second = OssUtils.signAsset("scrapper/asset/abc123", 256, immutable = true, mime = "image/png")

        assertEquals(first, second)
        listOf("Signature", "Expires", "OSSAccessKeyId").forEach {
            assertFalse(first.orEmpty().contains(it), "代理地址不该带签名参数 $it")
        }
    }

    /**
     * 封面只限宽不限高，代理地址必须如实反映这一点：nginx 侧的白名单靠 `w`/`h` 是否成对
     * 来区分 `m_fill`（图标裁成正方形）与 `m_lfit`（封面等比缩）。把 h 一起带出去，
     * 一张 1280×720 的截图会被裁成 640×640，正好毁掉"这个页面长什么样"这个唯一用途。
     */
    @Test
    fun `covers carry width only so nginx picks lfit`() {
        OssUtils.initProxyDomain("https://cdn.bookmarkify.cc")

        val url = OssUtils.signCover("scrapper/asset/cover1", mime = "image/png", immutable = true)

        assertEquals("https://cdn.bookmarkify.cc/oss/scrapper/asset/cover1?w=640", url)
    }

    /**
     * SVG/ICO 进不了 OSS 图片处理，代理地址同样不能带尺寸参数 —— 否则 nginx 会照白名单
     * 拼出 `x-oss-process`，OSS 直接 400，前端拿到一张碎图。
     */
    @Test
    fun `unprocessable formats reach the proxy without size params`() {
        OssUtils.initProxyDomain("https://cdn.bookmarkify.cc")

        val svg = OssUtils.signAsset("scrapper/asset/logo", 64, immutable = true, mime = "image/svg+xml")

        assertEquals("https://cdn.bookmarkify.cc/oss/scrapper/asset/logo", svg)
    }

    /**
     * 可变对象即便开着代理也必须走签名直连：代理下发的 `Cache-Control: immutable` 是不可撤销的，
     * 一个会被覆盖的 key 走过去，改动就被永久钉死。
     *
     * 这里 OSS 客户端未初始化，signAsset 的 runCatching 会兜底返回原始 ref —— 断言只关心
     * "没走到代理那条分支"。
     */
    @Test
    fun `mutable objects never reach the proxy`() {
        OssUtils.initProxyDomain("https://cdn.bookmarkify.cc")

        val url = OssUtils.signAsset("screenshot/page1", 640, immutable = false, mime = "image/png")

        assertFalse(url.orEmpty().contains("/oss/"), "会被原地覆盖的对象不能拿到永久缓存承诺")
    }

    /**
     * 外链（只做了 PROBE、库里存的是源站地址）原样返回，代理不该把手伸到别人家域名上。
     */
    @Test
    fun `foreign urls are returned untouched`() {
        OssUtils.initProxyDomain("https://cdn.bookmarkify.cc")

        val ref = "https://example.com/logo.png"
        assertEquals(ref, OssUtils.signAsset(ref, 64, immutable = true, mime = "image/png"))
    }
}
