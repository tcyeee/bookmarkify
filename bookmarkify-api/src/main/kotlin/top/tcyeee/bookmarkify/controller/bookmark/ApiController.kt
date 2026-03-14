package top.tcyeee.bookmarkify.controller.bookmark

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.throttle.Throttle
import top.tcyeee.bookmarkify.entity.WebsiteInfoVO
import top.tcyeee.bookmarkify.server.IApiService

@RestController
@Tag(name = "第三方API")
@RequestMapping("/api")
class ApiController(private val apiService: IApiService) {

    @Throttle
    @GetMapping("/website-info")
    @Operation(summary = "查询网站基础信息，传入域名或完整URL")
    fun websiteInfo(@RequestParam domain: String): WebsiteInfoVO = apiService.queryWebsiteInfo(domain)
}
