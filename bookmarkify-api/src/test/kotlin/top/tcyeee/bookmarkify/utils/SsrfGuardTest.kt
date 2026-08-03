package top.tcyeee.bookmarkify.utils

import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 本地抓取路径的 SSRF 防护。
 *
 * 用字面量 IP 而不是域名来测：这些断言不能依赖 DNS，否则在没有外网的构建机上会假失败。
 */
class SsrfGuardTest {

    private fun assertBlocked(url: String) {
        val ex = assertFailsWith<CommonException>("应当拒绝: $url") { SsrfGuard.assertPublic(url) }
        assertEquals(ErrorType.E308, ex.errorType, "拒绝的理由应当是 SSRF 而不是别的: $url")
    }

    @Test
    fun `blocks loopback`() {
        assertBlocked("http://127.0.0.1/admin")
        assertBlocked("http://127.9.9.9/")
        assertBlocked("http://[::1]/")
    }

    @Test
    fun `blocks rfc1918 private ranges`() {
        assertBlocked("http://10.0.0.1/")
        assertBlocked("http://172.16.0.1/")
        assertBlocked("http://192.168.1.1/")
    }

    /** 云厂商元数据端点就在这一段，是 SSRF 最典型的目标 */
    @Test
    fun `blocks link local metadata endpoint`() {
        assertBlocked("http://169.254.169.254/latest/meta-data/")
    }

    /** 100.64/10 不是 RFC1918，Java 的 isSiteLocalAddress 不认，但在容器网络里同样是内部地址 */
    @Test
    fun `blocks carrier grade nat range`() {
        assertBlocked("http://100.64.0.1/")
        assertBlocked("http://100.127.255.254/")
    }

    @Test
    fun `blocks any local and multicast`() {
        assertBlocked("http://0.0.0.0/")
        assertBlocked("http://224.0.0.1/")
    }

    /** fc00::/7 是 IPv6 的唯一本地地址，等价于 IPv4 的私有网段 */
    @Test
    fun `blocks ipv6 unique local`() {
        assertBlocked("http://[fd00::1]/")
        assertBlocked("http://[fc00::1]/")
    }

    /** 100.128.x 已经超出 100.64/10，是正常公网地址，不能误伤 */
    @Test
    fun `allows public addresses`() {
        SsrfGuard.assertPublic("http://1.1.1.1/")
        SsrfGuard.assertPublic("http://8.8.8.8/")
        SsrfGuard.assertPublic("http://100.128.0.1/")
        SsrfGuard.assertPublic("http://[2001:4860:4860::8888]/")
    }

    /** 解析不出 host 时必须拒绝：拿不到结论就不放行，是这类检查唯一安全的失败方向 */
    @Test
    fun `blocks urls without a host`() {
        assertBlocked("not-a-url")
        assertBlocked("file:///etc/passwd")
    }
}
