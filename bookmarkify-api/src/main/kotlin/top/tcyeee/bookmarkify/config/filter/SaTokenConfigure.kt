package top.tcyeee.bookmarkify.config.filter

import cn.dev33.satoken.interceptor.SaInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import top.tcyeee.bookmarkify.utils.StpKit


/**
 * @author tcyeee
 * @date 3/11/25 15:01
 */
@Configuration
class SaTokenConfigure(private val extensionTokenInterceptor: ExtensionTokenInterceptor) : WebMvcConfigurer {
    // 注册拦截器
    override fun addInterceptors(registry: InterceptorRegistry) {
        // USER 模块鉴权：拦截所有路径，排除 /admin/** 与 /extension/**
        // (/extension/** 面向浏览器插件，走独立的 AccessToken 鉴权，见 ExtensionTokenInterceptor)
        registry.addInterceptor(SaInterceptor { StpKit.USER.checkLogin() })
            .addPathPatterns("/**")
            .excludePathPatterns("/admin/**", "/extension/**")

        // ADMIN 模块鉴权：仅拦截 /admin/**
        registry.addInterceptor(SaInterceptor { StpKit.ADMIN.checkLogin() })
            .addPathPatterns("/admin/**")

        // 插件 AccessToken 鉴权：仅拦截 /extension/**
        registry.addInterceptor(extensionTokenInterceptor)
            .addPathPatterns("/extension/**")
    }
}
