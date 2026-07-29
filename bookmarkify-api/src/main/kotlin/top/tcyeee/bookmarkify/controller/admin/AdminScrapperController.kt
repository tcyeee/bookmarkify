package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeRequest
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.server.IApiService

/**
 * Scrapper 直通调试接口。
 *
 * 与 `/admin/website/liveness-check` 的区别：
 * - 那个只收一个 URL、返回扁平化后的少量字段，并且**有副作用**（命中已有书签时会覆盖落库）
 * - 这个接受**完整的 [ScrapeRequest]**、返回**未经任何加工的 [ScrapeResponse]**，且**不写任何库**
 *
 * 存在的意义是让管理后台能真正把 scrapper 的每个参数都试一遍 —— 抓取模式、提取模块开关、
 * 图片下载策略、缓存语义都能单独验证，而不必去线上翻日志猜行为。
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/scrapper")
class AdminScrapperController(
    private val apiService: IApiService,
) {

    /**
     * 用完整参数调一次 scrapper，原样返回其响应。
     *
     * 不做任何持久化，也不碰 `site_asset` / `bookmark` —— 纯粹的调试通道。
     * 失败时由 [IApiService.scrape] 抛出 `CommonException(E304)`，错误信息透传给前端。
     */
    @PostMapping("/scrape")
    @Operation(summary = "以完整参数调用 scrapper 并原样返回响应（无副作用）")
    fun scrape(@RequestBody request: ScrapeRequest): ScrapeResponse =
        apiService.scrape(request.url, request)
}
