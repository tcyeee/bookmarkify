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
class SaTokenConfigure : WebMvcConfigurer {
    // 注册拦截器
    override fun addInterceptors(registry: InterceptorRegistry) {
        // 如果是/admin/**,则使用admin解析
        // 否则使用 User解析
        registry.addInterceptor(SaInterceptor { StpKit.USER.checkLogin() }).addPathPatterns("/**")
    }
}
