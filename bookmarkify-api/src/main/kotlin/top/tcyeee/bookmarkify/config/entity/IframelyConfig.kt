package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bookmarkify.iframely")
data class IframelyConfig(
    var apiKey: String = "",
)
