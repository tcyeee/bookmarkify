package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * @author tcyeee
 * @date 4/22/23 13:44
 */
@Configuration
@ConfigurationProperties(prefix = "config")
data class ProjectConfig (
    var imgPath: String,          // 图片本地存储地址
    var imgPrefix: String,        // 图片前缀
    var uidCookieName: String,    // 匿名用户 cookie 名称
    var uidCookiePath: String,    // 匿名用户 cookie 路径
    var uidCookieMaxAge: Int,     // 匿名用户 cookie 过期时间（秒）
)