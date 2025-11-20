package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * @author tcyeee
 * @date 4/22/23 13:44
 */
@Configuration
@ConfigurationProperties(prefix = "config")
data class ProjectConfig(
    var imgPath: String = "",                              // 图片本地存储地址
    var imgPrefix: String = "https://file.bookmarkify.cc", // 图片前缀
)