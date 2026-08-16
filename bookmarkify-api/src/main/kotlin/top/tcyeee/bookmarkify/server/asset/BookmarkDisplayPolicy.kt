package top.tcyeee.bookmarkify.server.asset

import top.tcyeee.bookmarkify.entity.enums.DisplayMode

/**
 * 一个书签该显示什么**文案**。
 *
 * 与 [AssetRolePolicy] 同构：纯函数、无 IO、可离线测试。图标选哪张归那边，文案选哪个归这边，
 * 两者共同构成"这个格子长什么样"的全部策略。详见根目录 `SITE_LAYERING_DESIGN.md` §7。
 *
 * ## 为什么不能只按展示模式分档
 *
 * 原设计是「TILE 用站点短名，LIST 用页面标题」（`site_short_name` 存在的意义就是给空间受限的
 * 图标配文案，这一点 W3C 的 `short_name` 已经说清楚了）。但那条规则在深链上会塌掉：
 *
 * > 一屏 20 个 YouTube 视频磁贴，文案全都是 "YouTube"，用户没法区分任何两个。
 *
 * 所以真正的判据是两条正交的问题合起来：
 *
 * 1. **靠什么区分？** 跨站点靠图标区分（YouTube 图标 vs GitHub 图标），同站点内图标完全一样、
 *    只能靠文案区分。于是**深链的文案必须是页面级的**。
 * 2. **有多少空间？** TILE 只有一行短文案，LIST 有一整行。
 *
 * 两条叠加的结果就是下面这张表 —— 站点短名只在「首页 + TILE」这一格里胜出，那也正是它唯一
 * 真正合适的场景。
 *
 * | | TILE（大图 + 短文案） | LIST（小图 + 全名） |
 * |---|---|---|
 * | 首页 | 用户标题 → **站点短名** → 页面简称 → 品牌名 → host | 用户标题 → 页面标题 → 品牌名 → host |
 * | 深链 | 用户标题 → **页面标题** → 站点短名 → 页面简称 → host | 用户标题 → 页面标题 → host |
 *
 * ## 「页面简称」为什么必须在候选里
 *
 * `site.short_name` 只有一个来源：manifest 的 `short_name`。而绝大多数站点根本没有 manifest
 * —— 2026-08-16 查生产：92 个首页里只有 **15** 个有站点短名。若候选表止步于此，磁贴上的
 * "站点短名"这一档对 84% 的书签是空的，直接掉到品牌名/裸域名（bilibili 会显示成
 * `www.bilibili.com`），整条规则等于没生效。
 *
 * `page.app_name` 是同一件东西的另一个来源：manifest 短名**拿不到时**由 DeepSeek 从页面标题
 * 推断（见 `BookmarkServiceImpl.parseByApi`），同一批数据里有 **75** 个 —— "哔哩哔哩"、"豆瓣"、
 * "少数派" 全在这一列。它排在站点短名之后：站点那一层由首页抓取权威写入，而这一列是逐页的
 * LLM 推断，深链上尤其可能带偏。
 *
 * 只加在 TILE 两档，LIST 原封不动：列表行有完整空间，本来就该显示页面标题。
 *
 * ## 用户标题永远第一
 *
 * 改造前是 `title = appName ?: title ?: urlHost` —— 站点短名压在用户自己写的标题**之上**，
 * 用户改完名字看不到任何变化。这是个纯粹的优先级写反，不是取舍。
 */
object BookmarkDisplayPolicy {

    /**
     * 决定最终显示的文案。
     *
     * @param userTitle 用户自己改过的标题；**`null` 表示没改过**（不是"改成了空"）。
     *   这条区分依赖 `bookmark_user_link.title` 不再存创建时从页面拷来的快照，见
     *   `SITE_LAYERING_DESIGN.md` §6。
     * @param pageTitle 页面标题（`bookmark.title`）
     * @param siteShortName 站点短名（`site.short_name`，只来自 `manifest.short_name`，覆盖率低）
     * @param pageAppName 页面简称（`page.app_name`：manifest 短名，拿不到时由 DeepSeek 从标题推断）
     * @param siteBrandName 站点全名（`site.brand_name`，来自 `og:site_name` / `manifest.name`）
     * @param urlHost 兜底：什么都没有时至少显示域名，不要显示空白
     * @param isRootPage 是不是站点首页（`bookmark.isRootPage`）
     *
     * **刻意不做服务端截断。** TILE 的文案空间有限，但截断该由 CSS 省略号负责：服务端截过
     * 就再也拿不回全文，hover 提示、搜索高亮、无障碍朗读都会一起损失，而前端本来就必须处理
     * 长站点名的溢出。
     */
    fun title(
        userTitle: String?,
        pageTitle: String?,
        siteShortName: String?,
        pageAppName: String?,
        siteBrandName: String?,
        urlHost: String?,
        isRootPage: Boolean,
        mode: DisplayMode,
    ): String? {
        val candidates = when {
            // 首页 + 大图：短名唯一真正合适的场景。站点短名优先于页面简称，理由见类注释
            isRootPage && mode == DisplayMode.TILE ->
                listOf(userTitle, siteShortName, pageAppName, siteBrandName, urlHost)

            isRootPage ->
                listOf(userTitle, pageTitle, siteBrandName, urlHost)

            // 深链 + 大图：同站点的多个深链图标一模一样，只能靠页面标题区分；
            // 短名退成兜底（页面标题都没抓到时，"YouTube" 仍好于裸域名）
            mode == DisplayMode.TILE ->
                listOf(userTitle, pageTitle, siteShortName, pageAppName, urlHost)

            else ->
                listOf(userTitle, pageTitle, urlHost)
        }
        return candidates.firstOrNull { !it.isNullOrBlank() }
    }
}
