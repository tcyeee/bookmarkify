package top.tcyeee.bookmarkify.utils

import org.slf4j.LoggerFactory
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * 本地抓取路径（Jsoup / HttpUtil）的 SSRF 防护。
 *
 * ## 为什么只有这条路径需要它
 *
 * 主抓取链路走 `bookmarkify-scrapper`，SSRF 原语在那边（`scraper.rs`），目标地址由它校验，
 * API 自己一个字节都不发。但 `bookmarkify.config.use-third-party-parser = false` 时会切到
 * [WebsiteParser.parse]，那条路径由 **API 进程**直接去请求用户给的网址 —— 而 API 进程能看到
 * 数据库、Redis 与整个内网。
 *
 * `parseBookmarkExclusively` 里那道 `classifyLinkType != DOMAIN` 的前置检查挡不住这件事：
 * 它只认字面量（`localhost` / `127.0.0.1` / 裸 IP），而
 * - 一个普通域名完全可以把 A 记录指向 `169.254.169.254`（云厂商元数据端点）或 `10.x`；
 * - Jsoup 默认**跟随重定向**，一个公网地址 302 到内网地址同样绕过所有前置检查。
 *
 * 也就是说这是一个「改一行配置就变成漏洞」的开关。这个类把它关上。
 *
 * ## 做法
 *
 * 逐跳校验：每次发请求前把 host 解析成 IP，**所有**解析结果都必须是公网地址（一个域名可以有
 * 多条 A 记录，只查第一条等于没查）；跟随重定向时对每一跳重复这件事。
 *
 * 需要说明的是这挡不住 DNS rebinding 的极端情况（校验时解析到公网、真正建连时解析到内网）——
 * 那需要在连接层固定 IP，Jsoup 给不了这个钩子。对「本地开发用的降级解析路径」而言，
 * 挡住直连内网与重定向进内网已经覆盖了现实中的绝大多数，剩下的那点残余风险由
 * 「生产环境恒为 `use-third-party-parser = true`」承担。
 */
object SsrfGuard {

    private val log = LoggerFactory.getLogger(SsrfGuard::class.java)

    /** 跟随重定向的最大跳数。超过即判定为重定向环。 */
    const val MAX_REDIRECTS = 5

    /**
     * 断言这个网址指向的是公网地址，否则抛 [ErrorType.E308]。
     *
     * 解析不出 host、或 DNS 查不到任何地址时同样拒绝：拿不到结论就不放行，是这类检查
     * 唯一安全的失败方向。
     */
    fun assertPublic(url: String) {
        val host = runCatching { URI(url).host }.getOrNull()
            ?: throw CommonException(ErrorType.E308, "无法从网址中解析出主机名")

        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrElse {
            // DNS 查不到就是抓不到，归给"域名解析错误"更准确——这不是安全事件
            throw CommonException(ErrorType.E303, "域名解析失败: $host")
        }
        if (addresses.isEmpty()) throw CommonException(ErrorType.E303, "域名解析结果为空: $host")

        // 一个域名可以有多条 A 记录，只看第一条等于没看
        addresses.firstOrNull { it.isBlocked() }?.let { blocked ->
            log.warn("[SsrfGuard] 拒绝抓取指向内网的地址: host={}, resolved={}", host, blocked.hostAddress)
            throw CommonException(ErrorType.E308, "${ErrorType.E308.msg}（$host → ${blocked.hostAddress}）")
        }
    }

    /**
     * 这个地址是否属于「不许我方主动去连」的范围。
     *
     * 覆盖：环回、任意地址(0.0.0.0)、私有网段(10/172.16/192.168)、链路本地(169.254 —— 云厂商
     * 元数据端点就在这里)、组播、以及 IPv4 里 `isSiteLocalAddress` 覆盖不到的
     * CGNAT(100.64/10) 与 IPv6 唯一本地地址(fc00::/7)。
     */
    private fun InetAddress.isBlocked(): Boolean {
        if (isLoopbackAddress || isAnyLocalAddress || isLinkLocalAddress ||
            isSiteLocalAddress || isMulticastAddress
        ) return true

        return when (this) {
            // 100.64.0.0/10 运营商级 NAT：不是 RFC1918，Java 的 isSiteLocalAddress 不认，
            // 但在容器/云网络里同样是"内部地址"
            is Inet4Address -> address.let { (it[0].toInt() and 0xFF) == 100 && (it[1].toInt() and 0xFF) in 64..127 }
            // fc00::/7 IPv6 唯一本地地址，等价于 IPv4 的私有网段
            is Inet6Address -> (address[0].toInt() and 0xFE) == 0xFC
            else -> false
        }
    }
}
