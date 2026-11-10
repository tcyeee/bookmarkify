package top.tcyeee.bookmarkify.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter
import top.tcyeee.bookmarkify.config.monitor.LogContext

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
        // 把 X-Request-Id 暴露给浏览器。**必须显式列出**：CORS 默认只让 JS 读到七个安全响应头，
        // 自定义头不在其中，`allowedHeaders = *` 管的是**请求**头，与这件事无关。
        // 不加这一条，前端 `response.headers.get('X-Request-Id')` 恒为 null —— 于是报错提示里
        // 给不出请求号，用户报障时那条"拿这串就能查到全部现场"的链路在跨域部署下直接断掉
        // （本地 web:3000 → api:8001 跨域；线上 admin 与 api 也不同源）。
        config.addExposedHeader(LogContext.REQUEST_ID_HEADER)
        // 对接口配置跨域设置
        source.registerCorsConfiguration("/**", config)
        return CorsFilter(source)
    }
}