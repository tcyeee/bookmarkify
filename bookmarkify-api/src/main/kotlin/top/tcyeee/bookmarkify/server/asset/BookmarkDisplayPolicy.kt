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
 * | 首页 | 用户标题 → **站点短名** → 品牌名 → host | 用户标题 → 页面标题 → 品牌名 → host |
 * | 深链 | 用户标题 → **页面标题** → 站点短名 → host | 用户标题 → 页面标题 → host |
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
     * @param siteShortName 站点短名（`site.short_name`，来自 `manifest.short_name`）
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
        siteBrandName: String?,
        urlHost: String?,
        isRootPage: Boolean,
        mode: DisplayMode,
    ): String? {
        val candidates = when {
            // 首页 + 大图：站点短名唯一真正合适的场景
            isRootPage && mode == DisplayMode.TILE ->
                listOf(userTitle, siteShortName, siteBrandName, urlHost)

            isRootPage ->
                listOf(userTitle, pageTitle, siteBrandName, urlHost)

            // 深链 + 大图：同站点的多个深链图标一模一样，只能靠页面标题区分；
            // 短名退成兜底（页面标题都没抓到时，"YouTube" 仍好于裸域名）
            mode == DisplayMode.TILE ->
                listOf(userTitle, pageTitle, siteShortName, urlHost)

            else ->
                listOf(userTitle, pageTitle, urlHost)
        }
        return candidates.firstOrNull { !it.isNullOrBlank() }
    }
}
