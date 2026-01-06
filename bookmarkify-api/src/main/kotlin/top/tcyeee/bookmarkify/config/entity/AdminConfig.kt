package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * @author tcyeee
 * @date 1/5/26 19:35
 */
@ConfigurationProperties(prefix = "bookmarkify.admin.login")
data class AdminLoginConfig(
    var account: String = "",
    var password: String = ""
)
