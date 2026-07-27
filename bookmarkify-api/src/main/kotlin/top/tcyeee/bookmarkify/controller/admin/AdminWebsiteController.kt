package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.dto.WebsiteLivenessCheckParams
import top.tcyeee.bookmarkify.entity.dto.WebsiteLivenessCheckVO
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService

/**
 * 网站管理：书签链接类型(域名/本地/IP/其他)相关的管理端操作
 * @author tcyeee
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/website")
class AdminWebsiteController(
    private val bookmarkUserLinkService: IBookmarkUserLinkService,
    private val bookmarkService: IBookmarkService,
    private val apiService: IApiService,
) {

    // 对全部书签重新按 host 计算并回写 linkType(域名/本地/IP/其他)；同时批量检查全部书签是否 NSFW(涉黄/涉赌等)
    @PostMapping("/classify-link-type")
    fun classifyLinkType(): Map<String, Int> {
        val total = bookmarkUserLinkService.reclassifyAllLinkTypes()
        val (nsfwChecked, nsfwFlagged) = bookmarkService.checkNsfwForAll()
        return mapOf(
            "total" to total,
            "nsfwChecked" to nsfwChecked,
            "nsfwFlagged" to nsfwFlagged,
        )
    }

    // 任意 URL 活性检测：直接调用 scrapper /scrape 并原样返回其全部字段；不要求该 URL 已收录为书签，也不落库
    @PostMapping("/liveness-check")
    fun checkLiveness(@RequestBody params: WebsiteLivenessCheckParams): WebsiteLivenessCheckVO =
        runCatching { apiService.queryWebsiteInfo(params.url) }.fold(
            onSuccess = { vo ->
                WebsiteLivenessCheckVO(
                    success = true,
                    title = vo.title,
                    description = vo.description,
                    image = vo.image,
                    favicon = vo.favicon,
                    logo = vo.logo,
                    source = vo.source,
                    cached = vo.cached,
                    screenshot = vo.screenshot,
                )
            },
            onFailure = { e -> WebsiteLivenessCheckVO(success = false, errorMsg = e.message) },
        )
}
