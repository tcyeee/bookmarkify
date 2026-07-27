package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 自部署的 bookmarkify-scrapper 连接配置（接替原 iframely）。
 */
@ConfigurationProperties(prefix = "bookmarkify.scrapper")
data class ScrapperConfig(
    /** scrapper 服务基础地址，如 http://bookmarkify-scraper:3000 */
    var baseUrl: String = "",
    /**
     * 与 scrapper 的 `SCRAPER_AUTH_TOKEN` 对应的共享密钥；调用 /scrape、/ping 时
     * 作为 `Authorization: Bearer <token>` 发送。留空（默认）则不发送该 header——
     * 对应 scrapper 侧未配置 `SCRAPER_AUTH_TOKEN` 时不做鉴权的行为，两边需保持一致。
     */
    var authToken: String = "",
)
