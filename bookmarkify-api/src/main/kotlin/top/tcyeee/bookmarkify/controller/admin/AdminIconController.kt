package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.AssetVerdictRecomputeReport
import top.tcyeee.bookmarkify.entity.IconVerdictOverviewVO
import top.tcyeee.bookmarkify.entity.IconVerdictQueryParams
import top.tcyeee.bookmarkify.entity.IconVerdictSiteVO
import top.tcyeee.bookmarkify.server.asset.AssetVerdictRecomputeService
import top.tcyeee.bookmarkify.server.asset.IconVerdictAuditService

/**
 * 图标展示相关的后台接口。
 *
 * 目前只有「判定总览」一个用途：量化 `AssetRolePolicy` 在存量数据上的效果，供改规则时前后对比。
 * 改造计划见 `docs/ICON-DISPLAY-TODO.md`。
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/icon")
class AdminIconController(
    private val iconVerdictAuditService: IconVerdictAuditService,
    private val assetVerdictRecomputeService: AssetVerdictRecomputeService,
) {

    /** 判定总览：三档站点数 + 候选图数量分布 + 改进空间 */
    @PostMapping("/verdict-overview")
    fun verdictOverview(): IconVerdictOverviewVO = iconVerdictAuditService.overview()

    /** 判定下钻：一个站点一行。不分页，见 [IconVerdictQueryParams] 的类注释 */
    @PostMapping("/verdict-sites")
    fun verdictSites(@RequestBody params: IconVerdictQueryParams): List<IconVerdictSiteVO> =
        iconVerdictAuditService.sites(params)

    /**
     * 用当前规则重算存量资产的 role / quality / is_primary。
     *
     * `assignRoles` 是**写侧**规则，改了它不会作用于存量（判定被物化在抓取那一刻写的列里），
     * 而重跑并不需要重抓 —— 全部输入都还在行里。详见 `docs/ICON-DISPLAY-TODO.md` §3.0。
     *
     * 做成显式按钮而不是启动时自动跑：它会改写全站图标的显示结果，必须是一个有人按下、
     * 有日志、可复测的动作。`dryRun=true` 只统计不写库，改规则前先空跑一次看变更量。
     */
    @PostMapping("/recompute-verdict")
    fun recomputeVerdict(@RequestParam(defaultValue = "false") dryRun: Boolean): AssetVerdictRecomputeReport =
        assetVerdictRecomputeService.recomputeAll(dryRun)
}
