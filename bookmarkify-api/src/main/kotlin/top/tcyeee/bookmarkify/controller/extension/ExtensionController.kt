package top.tcyeee.bookmarkify.controller.extension

import top.tcyeee.bookmarkify.entity.dto.scrape.cached
import top.tcyeee.bookmarkify.entity.dto.scrape.description
import top.tcyeee.bookmarkify.entity.dto.scrape.faviconUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.logoUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.primarySource
import top.tcyeee.bookmarkify.entity.dto.scrape.screenshotUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.socialUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.title
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.throttle.Throttle
import top.tcyeee.bookmarkify.entity.ExtensionSiteInfoVO
import top.tcyeee.bookmarkify.server.IApiService

/**
 * 面向浏览器插件的只读接口：鉴权走 [top.tcyeee.bookmarkify.config.filter.ExtensionTokenInterceptor]
 * (请求头 X-Extension-Token)，与 Sa-Token 登录会话完全独立，见根目录 ACCESS_TOKEN_DESIGN.md。
 *
 * @author tcyeee
 */
@RestController
@Tag(name = "插件专用接口")
@RequestMapping("/extension")
class ExtensionController(private val apiService: IApiService) {

    // 无 satoken 会话可用于限流键，会自动回退为按客户端 IP 限流(见 ThrottleAspect)
    @Throttle(interval = 300)
    @GetMapping("/site-info")
    @Operation(summary = "查询网站标题与图标，供插件在浏览页面时展示/预填")
    fun siteInfo(@RequestParam url: String): ExtensionSiteInfoVO {
        val scraped = apiService.queryWebsiteInfo(url)
        return ExtensionSiteInfoVO(title = scraped.title, favicon = scraped.faviconUrl)
    }
}
