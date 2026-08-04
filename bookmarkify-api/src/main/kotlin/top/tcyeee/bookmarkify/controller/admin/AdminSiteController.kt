package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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

    /**
     * 单个站点。合并视图带着 `?siteId=` 直接进来时，那个站点未必在左侧列表的当前分页里。
     *
     * 站点不存在返回 404 而不是空对象：合并视图据此把 URL 上的陈旧 siteId 清掉，
     * 给一个字段全空的壳只会让它当成"有这么个站，只是什么都没抓到"。
     */
    @GetMapping("/{siteId}")
    fun detail(@PathVariable siteId: String): ResponseEntity<SiteAdminVO> =
        siteService.adminDetail(siteId)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
}
