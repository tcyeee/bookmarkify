package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 自托管 GoatCounter 埋点统计实例的连接配置。
 * 后台分析看板通过 REST API 读取 bookmarkify 站点（site 3）的真实访问数据。
 */
@ConfigurationProperties(prefix = "bookmarkify.goatcounter")
data class GoatCounterConfig(
    /** GoatCounter 站点地址（含协议），如 https://bookmarkify.stats.viii.me */
    var baseUrl: String = "",
    /** 只读 API Token（perm=64 stats-read），通过 Authorization: Bearer 携带 */
    var token: String = "",
)
