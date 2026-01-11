package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * @author tcyeee
 * @date 4/22/23 13:44
 */
data class DefaultBackgroundGradientConfig(
    var gradient: List<String> = emptyList(),
    var direction: Int = 0,
    var name: String = "",
)

@ConfigurationProperties(prefix = "bookmarkify.config")
class ProjectConfig(
    var imgPath: String,          // 图片本地存储地址
    var imgPrefix: String,        // 图片前缀
    var uidCookieName: String,    // 匿名用户 cookie 名称
    var uidCookiePath: String,    // 匿名用户 cookie 路径
    var uidCookieMaxAge: Int,     // 匿名用户 cookie 过期时间（秒）
    var defaultBookmarkify: List<String>,     // 用户初始化时候的默认书签
    var defaultBackgroundImage: List<String>, // 默认背景图片
    var defaultBackgroundGradient: List<DefaultBackgroundGradientConfig> = emptyList(), // 默认渐变背景
    var maxCustomBackgroundCount: Int = 5,     // 自定义背景（图片/渐变）最大数量
)