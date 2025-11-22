package top.tcyeee.bookmarkify.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * SwaggerConfig
 *
 * @author tcyeee
 * @date 3/11/25 21:33
 */
@Configuration
class SwaggerConfig {
    @Bean
    fun customOpenAPI(): OpenAPI {
        val description = Info()
            .title("书签鸭API")
            .description("后端接口平台")
            .version("1.1.0")
        return OpenAPI(SpecVersion.V31).info(description)
    }
}
