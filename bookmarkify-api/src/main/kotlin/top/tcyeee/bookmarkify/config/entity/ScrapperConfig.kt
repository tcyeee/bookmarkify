package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 自部署的 bookmarkify-scrapper 连接配置（接替原 iframely）。
 */
@ConfigurationProperties(prefix = "bookmarkify.scrapper")
data class ScrapperConfig(
    /** scrapper 服务基础地址，如 http://bookmarkify-scraper:3000 */
    var baseUrl: String = "",
)
