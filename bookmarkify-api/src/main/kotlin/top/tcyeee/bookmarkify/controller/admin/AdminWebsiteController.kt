package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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
) {

    // 对全部书签重新按 host 计算并回写 linkType(域名/本地/IP/其他)
    @PostMapping("/classify-link-type")
    fun classifyLinkType(): Map<String, Int> =
        mapOf("total" to bookmarkUserLinkService.reclassifyAllLinkTypes())
}
