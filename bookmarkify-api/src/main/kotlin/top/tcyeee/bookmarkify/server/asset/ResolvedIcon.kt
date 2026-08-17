package top.tcyeee.bookmarkify.server.asset

import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode

/**
 * 一次图标解析的结果：**这个书签在某个展示模式下到底渲染成什么**。
 *
 * [monogram] 为 true 时前端应当放弃图片改用首字母色块 —— 不是「加载失败」，而是
 * 「这个站没提供够格的图，硬拉伸只会更难看」。
 *
 * ## 为什么是独立文件而不是嵌在 `IconResolver` 里
 *
 * 它以前是 `SiteAssetResolver.ResolvedLogo`，于是 `Response.kt`（1100 行的 VO 总表）
 * 为了给一个字段命名，必须 `import` 一个 `@Service`。结果类型是**契约**，不该把持有它的
 * 那个服务一起拖进依赖图里 —— 尤其当依赖它的是 VO 层这种本该无依赖的地方。
 */
data class ResolvedIcon(
    val url: String? = null,
    val role: AssetRole? = null,
    val quality: AssetQuality? = null,
    val isVector: Boolean = false,
    val monogram: Boolean = true,
) {
    companion object {
        /** 无任何可用资产时的空结果，前端据此走首字母色块 */
        val EMPTY = ResolvedIcon()
    }
}

/**
 * 一条书签渲染所需的**全部**图标，外加它们是按哪个模式算出来的。
 *
 * ## 为什么是一个对象而不是两个参数
 *
 * `BookmarkShow.initDisplay` 从前收 `(resolved, mode)` 两个参数，于是「用 TILE 选图、用 LIST
 * 选文案」这种自相矛盾的组合在类型上完全合法，只能靠一段注释拦着。把模式和它对应的解析结果
 * 绑成一个值之后，那种写法根本构造不出来。
 *
 * ## 为什么有两份图标
 *
 * 同一条书签在首页被渲染**两次**：置顶区是 56px 的磁贴，下方文件夹卡片里还有一行 20px 的列表行。
 * 一份 VO 供两处使用，只带一份图标就必然有一处是错的 —— 而在 2026-08-17 之前正是如此：整棵树
 * 按 LIST 解析，置顶区拿到 FAVICON 优先、签在 64px、且不走色块兜底的那一份，塞进 56px 的格子
 * （2× 屏需要 112px）必糊。文案侧早就有 `tileTitle` 解决了同一个问题，图标侧只是漏了。
 *
 * [tileIcon] 在 `mode == TILE` 时与 [icon] 是同一份，不额外解析。
 */
data class DisplayIcons(
    val mode: DisplayMode,
    /** 按 [mode] 解析的结果，对应 `BookmarkShow.logo` */
    val icon: ResolvedIcon,
    /** 按 [DisplayMode.TILE] 解析的结果，对应 `BookmarkShow.tileLogo`（置顶区磁贴用） */
    val tileIcon: ResolvedIcon,
) {
    companion object {
        /** 这条书签没有 pageId、或压根没解析出东西时的空结果 */
        fun empty(mode: DisplayMode) = DisplayIcons(mode, ResolvedIcon.EMPTY, ResolvedIcon.EMPTY)
    }
}
