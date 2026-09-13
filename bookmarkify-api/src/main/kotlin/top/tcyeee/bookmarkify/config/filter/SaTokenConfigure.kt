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
        // USER 模块鉴权：拦截所有路径，排除 /admin/**、/extension/**、/internal/** 与 /error
        // (/extension/** 面向浏览器插件，走独立的 AccessToken 鉴权，见 ExtensionTokenInterceptor)
        // (/internal/** 是 nginx 缓存代理的回源入口，机器对机器，走共享密钥而非用户会话；
        //  它自己在 InternalAssetController 里把门看着 —— 密钥 + 账本校验 + 不可变校验三道)
        // (/error 是 Spring Boot 的默认错误页；鉴权拦截器若对它生效，直接访问 /error 会先抛
        //  NotLoginException，触发容器的 ErrorPage 转发又回到 /error 再抛一次——第二次绕过了
        //  GlobalExceptionHandler，被 Tomcat 当未处理异常打成 ERROR 日志，会把机器人的一次
        //  无害探测伪装成「连续报错」)
        registry.addInterceptor(SaInterceptor { StpKit.USER.checkLogin() })
            .addPathPatterns("/**")
            .excludePathPatterns("/admin/**", "/extension/**", "/internal/**", "/error")

        // ADMIN 模块鉴权：仅拦截 /admin/**
        registry.addInterceptor(SaInterceptor { StpKit.ADMIN.checkLogin() })
            .addPathPatterns("/admin/**")

        // 插件 AccessToken 鉴权：仅拦截 /extension/**
        registry.addInterceptor(extensionTokenInterceptor)
            .addPathPatterns("/extension/**")
    }
}
