package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.SiteAdminVO
import top.tcyeee.bookmarkify.entity.SiteSearchParams
import top.tcyeee.bookmarkify.server.ISiteService

/**
 * 站点(域名)管理。
 *
 * 与 [AdminWebsiteController] 分开：那边是几个一次性的批量操作入口（重算 linkType、
 * 任意 URL 活性探测），这边是 `site` 这张表的常规读取。合在一起会让「网站」这个词
 * 同时指两件事。
 *
 * @author tcyeee
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/site")
class AdminSiteController(
    private val siteService: ISiteService,
) {

    /** 全部站点，一个域名一行。筛选项见 [SiteSearchParams]。 */
    @PostMapping("/all")
    fun listAll(@RequestBody params: SiteSearchParams): IPage<SiteAdminVO> =
        siteService.adminListAll(params)
}
