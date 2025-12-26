package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * @author tcyeee
 * @date 4/22/23 13:44
 */
@ConfigurationProperties(prefix = "config")
data class ProjectConfig(
    var imgPath: String,          // 图片本地存储地址
    var imgPrefix: String,        // 图片前缀
    var uidCookieName: String,    // 匿名用户 cookie 名称
    var uidCookiePath: String,    // 匿名用户 cookie 路径
    var uidCookieMaxAge: Int,     // 匿名用户 cookie 过期时间（秒）
    var defaultBookmarkify: Array<String>,     // 匿名用户 cookie 过期时间（秒）
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ProjectConfig

        if (uidCookieMaxAge != other.uidCookieMaxAge) return false
        if (imgPath != other.imgPath) return false
        if (imgPrefix != other.imgPrefix) return false
        if (uidCookieName != other.uidCookieName) return false
        if (uidCookiePath != other.uidCookiePath) return false
        if (!defaultBookmarkify.contentEquals(other.defaultBookmarkify)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uidCookieMaxAge
        result = 31 * result + imgPath.hashCode()
        result = 31 * result + imgPrefix.hashCode()
        result = 31 * result + uidCookieName.hashCode()
        result = 31 * result + uidCookiePath.hashCode()
        result = 31 * result + defaultBookmarkify.contentHashCode()
        return result
    }
}