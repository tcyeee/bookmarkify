package top.tcyeee.bookmarkify.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "这份字节能不能交给 OSS 图片处理缩放"的判据。
 *
 * 判错的代价是不对称的，这决定了这里每条用例该往哪边靠：
 * - **误判成能处理** → 请求带上 `x-oss-process=image/resize`，OSS 直接 400
 *   (`This image format is not supported`, EC 0040-00000005)。注意它不是回退成原图，
 *   是整个请求失败 —— 用户看到的是一张碎图。
 * - **误判成不能处理** → 原图直出，多下几 KB，肉眼无差别。
 *
 * 所以白名单必须是白名单：认不出的一律不缩放。
 */
class OssImageProcessTest {

    @Test
    fun `raster formats oss can process are resized`() {
        listOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")
            .forEach { assertTrue(OssUtils.canImageProcess(it), "$it 应当可缩放") }
    }

    /**
     * 线上这次故障的直接原因：`/favicon.ico` 的 `isVector` 是 false（它确实不是矢量图），
     * 于是一路带着 `image/resize,m_fill,w_64,h_64` 打到 OSS，整张图 400。
     */
    @Test
    fun `ico is not processable even though it is not a vector`() {
        assertFalse(OssUtils.canImageProcess("image/x-icon", isVector = false))
        assertFalse(OssUtils.canImageProcess("image/vnd.microsoft.icon", isVector = false))
    }

    @Test
    fun `svg is rejected by mime and by the isVector flag alike`() {
        assertFalse(OssUtils.canImageProcess("image/svg+xml"))
        assertFalse(OssUtils.canImageProcess(mime = null, isVector = true))
        // 两个信号打架时以"不缩放"为准：多下几 KB 好过碎图
        assertFalse(OssUtils.canImageProcess("image/png", isVector = true))
    }

    @Test
    fun `mime parameters and casing do not defeat the whitelist`() {
        assertTrue(OssUtils.canImageProcess("image/jpeg; charset=binary"))
        assertTrue(OssUtils.canImageProcess("IMAGE/PNG"))
        assertFalse(OssUtils.canImageProcess("image/svg+xml; charset=utf-8"))
    }

    /** 认不出的格式一律不缩放 —— 白名单的意义就在这条 */
    @Test
    fun `unknown mime types are not resized`() {
        assertFalse(OssUtils.canImageProcess("application/octet-stream"))
        assertFalse(OssUtils.canImageProcess("text/html"))
    }

    /**
     * mime 为空只可能是改造前写入、从未记过这一列的存量行。这时退回看扩展名 ——
     * 老 key 还带着扩展名，内容寻址的新 key 不带，所以这层只对存量有效。
     */
    @Test
    fun `without a mime the key extension decides`() {
        assertTrue(OssUtils.canImageProcess(null, objectName = "background/abc.png"))
        assertFalse(OssUtils.canImageProcess(null, objectName = "avatar/abc.svg"))
        assertFalse(OssUtils.canImageProcess(null, objectName = "scrapper/asset/abc.ico"))
        // 内容寻址的 key 没有扩展名可看；这条路径上 mime 一定有值，兜底放行即可
        assertTrue(OssUtils.canImageProcess(null, objectName = "scrapper/asset/2681561eb24e7435"))
    }
}
