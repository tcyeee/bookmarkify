package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bookmarkify.deepseek")
data class DeepSeekConfig(
    var apiKey: String = "",
)
