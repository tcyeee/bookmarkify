package top.tcyeee.bookmarkify.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

@Configuration
class CorsFilterConfig {

    @Bean
    fun corsFilter(): CorsFilter {
        val source = UrlBasedCorsConfigurationSource()
        val config = CorsConfiguration()
        config.allowCredentials = true
        // 设置访问源地址
        config.addAllowedOriginPattern("*")
        // 设置访问源请求头
        config.addAllowedHeader("*")
        // 设置访问源请求方法
        config.addAllowedMethod("*")
        // 对接口配置跨域设置
        source.registerCorsConfiguration("/**", config)
        return CorsFilter(source)
    }
}