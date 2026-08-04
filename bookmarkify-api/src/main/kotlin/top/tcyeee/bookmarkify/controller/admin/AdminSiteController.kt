package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.SiteAdminVO
import top.tcyeee.bookmarkify.entity.SiteBasicInfoUpdateParams
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
     * 站点不存在时返回 `data: null`，**不是** HTTP 404：本项目全站走 `ResultWrapper` 信封，
     * 而 [GlobalExceptionHandler.beforeBodyWrite][top.tcyeee.bookmarkify.config.exception.GlobalExceptionHandler]
     * 会把空 body 一律包成 `ok=true`，于是 `ResponseEntity.notFound()` 发出去的是
     * 「HTTP 404 + `{ok:true, code:0}`」—— 状态码说没找到、信封说成功，前端拦截器按信封
     * 判定则读成成功、按状态码判定则弹一个没有 msg 的通用错误提示。null 让两边一致，
     * 调用方判空即可，也和「取不到」与「请求失败」必须分开这件事对上。
     */
    @GetMapping("/{siteId}")
    fun detail(@PathVariable siteId: String): SiteAdminVO? = siteService.adminDetail(siteId)

    /**
     * 手工编辑站点信息（品牌名/短名/人工认证/NSFW 纠正）。
     *
     * 这个端点补的是一条断掉的运营动线：`brandNameEmpty` / `verifyFlag` 这些筛选项一直
     * 筛得出「需要人工过一遍」的站点，却没有任何地方能改它们。
     *
     * 返回改完之后的完整快照（与 [detail] 同构），调用方就地替换列表行即可，不必整页重查。
     */
    @PostMapping("/{siteId}")
    fun updateBasicInfo(
        @PathVariable siteId: String,
        @RequestBody params: SiteBasicInfoUpdateParams,
    ): SiteAdminVO = siteService.adminUpdateBasicInfo(siteId, params)
}
