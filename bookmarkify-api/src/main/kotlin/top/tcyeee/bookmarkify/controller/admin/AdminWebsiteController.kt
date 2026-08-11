package top.tcyeee.bookmarkify.controller.admin

import top.tcyeee.bookmarkify.entity.dto.scrape.cached
import top.tcyeee.bookmarkify.entity.dto.scrape.description
import top.tcyeee.bookmarkify.entity.dto.scrape.faviconUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.logoUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.primarySource
import top.tcyeee.bookmarkify.entity.dto.scrape.screenshotUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.socialUrl
import top.tcyeee.bookmarkify.entity.dto.scrape.title
import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.dto.WebsiteLivenessCheckParams
import top.tcyeee.bookmarkify.entity.dto.WebsiteLivenessCheckVO
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.admin.IBookmarkAdminService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.repair.DeepLinkSplitRepair

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
    private val bookmarkAdminService: IBookmarkAdminService,
    private val apiService: IApiService,
    private val deepLinkSplitRepair: DeepLinkSplitRepair,
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

    /**
     * 一次性修复：把此前被合并成一条的深链拆开（`?v=A` 与 `?v=B` 曾共用一条 canonical 记录）。
     *
     * **先用 `dryRun=true` 跑一遍看影响面**，确认 `repointed` 的量级合理再真正执行。
     * 幂等，可重复调用；拆出来的新记录不在这里触发抓取，交给 `checkAll()` 按自己的节奏消费
     * （返回的 `awaitingCrawl` 就是待抓数量）。
     *
     * 详见根目录 `SITE_LAYERING_DESIGN.md` §8 第 4 步。
     */
    @PostMapping("/repair-deep-link-split")
    fun repairDeepLinkSplit(@RequestParam(defaultValue = "true") dryRun: Boolean): DeepLinkSplitRepair.Report =
        deepLinkSplitRepair.run(dryRun = dryRun)

    // 任意 URL 活性检测：直接调用 scrapper /scrape 并原样返回其全部字段；不要求该 URL 已收录为书签。
    // 抓取成功且该 URL 命中已有 canonical 书签时，同步覆盖持久化该书签(标题/简介/图标/活性状态)；
    // 未命中已有书签或抓取失败时不落库。
    @PostMapping("/liveness-check")
    fun checkLiveness(@RequestBody params: WebsiteLivenessCheckParams): WebsiteLivenessCheckVO =
        runCatching { apiService.queryWebsiteInfo(params.url) }.fold(
            onSuccess = { vo ->
                val synced = bookmarkAdminService.adminSyncFromExternalScrape(params.url, vo)
                WebsiteLivenessCheckVO(
                    success = true,
                    title = vo.title,
                    description = vo.description,
                    image = vo.socialUrl,
                    favicon = vo.faviconUrl,
                    logo = vo.logoUrl,
                    source = vo.primarySource,
                    cached = vo.cached,
                    screenshot = vo.screenshotUrl,
                    synced = synced,
                )
            },
            onFailure = { e -> WebsiteLivenessCheckVO(success = false, errorMsg = e.message) },
        )
}
