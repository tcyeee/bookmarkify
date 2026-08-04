package top.tcyeee.bookmarkify.server.asset

import top.tcyeee.bookmarkify.entity.dto.scrape.AssetExtractor
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import java.time.LocalDateTime

/**
 * 把 scrapper 报告的**事实**（[AssetExtractor]，这张图从哪个标签拿到的）映射成书签鸭的
 * **判断**（[AssetRole] 用途 + [AssetQuality] 可信度），并按展示模式选出该用哪一张。
 *
 * 这是整套重构里唯一需要业务判断的地方，也是刻意把它单独成文件的原因：
 * "apple-touch-icon 到底算不算 LOGO"这类规则以后会变，改这里即可 —— 不用改 scrapper，
 * 不用重抓，不用动数据库结构。
 */
object AssetRolePolicy {

    /**
     * 出处 → (用途, 可信度) 的映射表。
     *
     * 判定 [AssetQuality.TRUSTED] 的标准是"该来源在规范里语义明确"：
     * - `JSON_LD_ORG_LOGO` 是 schema.org 的 `Organization.logo`，明确就是品牌 LOGO；
     * - `MANIFEST_ICON` 是 PWA 规范里站点自己声明的多尺寸图标，也是明确的；
     * - 而 `APPLE_TOUCH_ICON` / `LINK_ICON` 本质是 favicon 的变体，把它们当 LOGO 用
     *   属于借用，故标 [AssetQuality.DEGRADED]。
     */
    private val TABLE: Map<AssetExtractor, Pair<AssetRole, AssetQuality>> = mapOf(
        // ── 明确的图标 ──
        AssetExtractor.LINK_ICON to (AssetRole.FAVICON to AssetQuality.TRUSTED),
        AssetExtractor.LINK_MASK_ICON to (AssetRole.FAVICON to AssetQuality.TRUSTED),
        AssetExtractor.FAVICON_ICO_FALLBACK to (AssetRole.FAVICON to AssetQuality.DEGRADED),
        // apple-touch-icon 是 iOS 主屏图标：尺寸够大，当 FAVICON 用是可信的
        AssetExtractor.APPLE_TOUCH_ICON to (AssetRole.FAVICON to AssetQuality.TRUSTED),
        AssetExtractor.MS_TILE_IMAGE to (AssetRole.FAVICON to AssetQuality.DEGRADED),

        // ── 明确的品牌 LOGO ──
        AssetExtractor.JSON_LD_ORG_LOGO to (AssetRole.LOGO to AssetQuality.TRUSTED),
        AssetExtractor.MANIFEST_ICON to (AssetRole.LOGO to AssetQuality.TRUSTED),

        // ── 社交分享图 ──
        AssetExtractor.OG_IMAGE to (AssetRole.SOCIAL to AssetQuality.TRUSTED),
        AssetExtractor.TWITTER_IMAGE to (AssetRole.SOCIAL to AssetQuality.TRUSTED),
        AssetExtractor.JSON_LD_IMAGE to (AssetRole.SOCIAL to AssetQuality.DEGRADED),
        // 站点公开 API 给出的封面。出处是站点自己的接口，比页面里的 og:image 只强不弱
        // （og:image 也是同一份数据渲染出来的），故同为 TRUSTED
        AssetExtractor.SITE_API_COVER to (AssetRole.SOCIAL to AssetQuality.TRUSTED),
    )

    /**
     * LOGO 缺位时，可以从哪些出处降级借用一张来充数。
     *
     * 借来的一律标 [AssetQuality.DEGRADED]，让渲染层知道"这不是真 LOGO"。
     */
    private val LOGO_FALLBACK_ORDER = listOf(
        AssetExtractor.APPLE_TOUCH_ICON,
        AssetExtractor.MS_TILE_IMAGE,
        AssetExtractor.LINK_ICON,
    )

    /** 认定"图标足够大，可以撑起大图展示"的最小边长（CSS 像素）。 */
    const val TILE_MIN_SIZE = 128

    /**
     * 站点图标最多陈旧到什么程度，[divergesFromSite] 还敢据此下"这一页是另一个产品"的结论。
     *
     * 60 天 = 内容重抓周期（30 天）的两倍。首页每轮重抓都会整体替换站点图标，所以一个还
     * 有人收藏首页的站点永远落在这个窗口里；超出说明这份图标快照久未验证，此时零交集更
     * 可能是站点换了图标而不是页面换了产品。
     */
    private const val SITE_ICON_MAX_AGE_DAYS = 60L

    /** 未知出处一律按社交图之外的最低档处理，不至于让新枚举把整条流程打挂。 */
    fun classify(extractor: AssetExtractor): Pair<AssetRole, AssetQuality> =
        TABLE[extractor] ?: (AssetRole.FAVICON to AssetQuality.DEGRADED)

    /**
     * 这个用途的图该挂在哪一层 —— 也是一条**策略**，不是事实，所以和 role 的判定放在一起。
     *
     * 判据仍是那个问题：换同域下另一个页面，这张图会变吗？图标不会（全站一套），
     * 社交图和截图会（就是页面内容本身）。详见根目录 `SITE_LAYERING_DESIGN.md` §5。
     *
     * 必须在 [assignRoles] **之后**调用：第三遍会把借来的 favicon 改判成 LOGO，
     * 归属得按最终的 role 算（这两者都归 SITE，结论不变，但顺序上的依赖是真实存在的）。
     */
    fun ownerTypeOf(role: AssetRole): AssetOwnerType = when (role) {
        AssetRole.FAVICON, AssetRole.LOGO -> AssetOwnerType.SITE
        AssetRole.SOCIAL, AssetRole.SCREENSHOT -> AssetOwnerType.PAGE
    }

    /**
     * 这一页声明的图标，是不是**跟本站点完全不是一套**。
     *
     * ## 要解决的问题
     *
     * [ownerTypeOf] 把图标一律判给 SITE，前提是"一个 host 就是一个产品"。这个前提对
     * YouTube、GitHub 成立，对 `tools.example.com/tools/a` 与 `/tools/b` 这类**一个域名下
     * 塞了多个互不相关产品**的站点不成立 —— 它们会共用同一张图标，磁贴上长得一模一样。
     *
     * Web App Manifest 的 `scope` 就是标准给这件事的答案（一个 origin 可以承载多个应用，
     * 各有自己的 `name`/`icons`），但那要求站点正确声明 scope，且得改跨服务契约。
     * 字节哈希是同一个判断的**事后版本**：真把子路径当独立产品做的站点，必然会给它配一张
     * 不同的图标 —— 那张图的 sha256 一比就露出来了，不需要站点配合任何声明。
     *
     * ## 判据刻意保守：**必须毫无交集**
     *
     * 只要本页声明的图标里有**任何一张**与站点已有图标字节相同，就认为还是同一个产品。
     * 部分重叠（共用 favicon、但多带了一张自己的 apple-touch-icon）是普通站点的常态，
     * 按"有一张不同就算独立产品"去判会让绝大多数深链都误判成独立产品，那等于取消了站点层。
     *
     * 两侧**都必须有可比的哈希**才敢下结论：`assets.download` 是 PROBE 之外的模式、
     * 或该张取回失败时 `contentHash` 为空，此时无从比较，一律返回 false 走原有行为
     * （宁可两个产品共用图标，也不要凭空把图标钉死在页面上）。
     *
     * ## 站点图标必须是新鲜的
     *
     * 「零交集」有**两个**可能的解释，字节本身分不开它们：
     *
     * 1. 这一页是同域下的另一个产品（本方法要抓的）；
     * 2. **站点换了图标**，而库里那份还是旧的。
     *
     * 第 2 种不是假想：深链抓取从不刷新 SITE 行（[SiteAssetWriter.fillMissingAssets] 在站点
     * 已有图标时整批跳过），所以一次改版之后、首页被重抓之前，该域名下**每一条**深链都与
     * 陈旧的 SITE 行零交集，于是每一页都把图标钉死在自己身上 —— 一页一套图标，正是站点层
     * 要消除的重复。TILE 下还更糟：借来的单张图标是 DEGRADED，`shouldFallbackToMonogram`
     * 会让这些磁贴直接退成首字母色块，而站点其实有一张正确且 TRUSTED 的 LOGO。
     *
     * 所以要求站点图标在 [SITE_ICON_MAX_AGE_DAYS] 之内被抓到过。超出就认为"分不清"，
     * 按本方法一贯的保守取向返回 false。代价是老站点上真正的多产品域名会漏判，
     * 而漏判只是回到分层前的共用图标，误判则是把整站的图标打散。
     *
     * @param siteIcons 站点当前已有的 FAVICON/LOGO 行
     * @param pageIcons 本次抓取从这一个页面声明里投影出来的 FAVICON/LOGO 行
     * @param now 判定基准时刻，仅供测试注入
     */
    fun divergesFromSite(
        siteIcons: List<SiteAssetEntity>,
        pageIcons: List<SiteAssetEntity>,
        now: LocalDateTime = LocalDateTime.now(),
    ): Boolean {
        val usableSiteIcons = siteIcons.filter { it.renderable() }
        val siteHashes = usableSiteIcons.mapNotNull { it.contentHash }.toSet()
        if (siteHashes.isEmpty()) return false
        val pageHashes = pageIcons.filter { it.renderable() }.mapNotNull { it.contentHash }.toSet()
        if (pageHashes.isEmpty()) return false
        if (siteHashes.intersect(pageHashes).isNotEmpty()) return false

        // 取最新的一张：只要站点图标集里有任何一张是近期抓到的，这份快照就还能代表"站点现在长什么样"
        val freshest = usableSiteIcons.maxOfOrNull { it.fetchedAt } ?: return false
        return freshest.isAfter(now.minusDays(SITE_ICON_MAX_AGE_DAYS))
    }

    /**
     * 给一批**同一书签**的资产定角色、定质量、定 primary，返回可直接落库的列表。
     *
     * 两条降级规则在这里生效：
     * 1. **hash 相同即降级**：某张 LOGO 与该站任一 FAVICON 字节完全一致时，说明它只是
     *    favicon 换了个 rel 名字，[AssetQuality] 压到 DEGRADED。这是前几轮反复提到的
     *    "很多站根本没有独立 LOGO"在数据层的落点。
     * 2. **LOGO 缺位时借用**：完全没有可信 LOGO 时，按 [LOGO_FALLBACK_ORDER] 借一张，
     *    并标记为降级。
     */
    fun assignRoles(assets: List<SiteAssetEntity>): List<SiteAssetEntity> {
        if (assets.isEmpty()) return assets

        // 第一遍：查表定 role/quality
        assets.forEach { asset ->
            val extractor = runCatching { AssetExtractor.valueOf(asset.extractor) }
                .getOrDefault(AssetExtractor.UNKNOWN)
            val (role, quality) = classify(extractor)
            asset.role = role
            asset.quality = quality
        }

        // 第二遍：hash 撞上 FAVICON 的 LOGO 一律降级
        val faviconHashes = assets
            .filter { it.role == AssetRole.FAVICON }
            .mapNotNull { it.contentHash }
            .toSet()
        assets.filter { it.role == AssetRole.LOGO }
            .filter { it.contentHash != null && it.contentHash in faviconHashes }
            .forEach { it.quality = AssetQuality.DEGRADED }

        // 第三遍：没有任何可渲染的 LOGO 时，从 favicon 家族借一张顶上
        val hasUsableLogo = assets.any { it.role == AssetRole.LOGO && it.renderable() }
        if (!hasUsableLogo) {
            LOGO_FALLBACK_ORDER.firstNotNullOfOrNull { wanted ->
                assets.firstOrNull { it.extractor == wanted.name && it.renderable() }
            }?.let { borrowed ->
                borrowed.role = AssetRole.LOGO
                borrowed.quality = AssetQuality.DEGRADED
            }
        }

        // 第四遍：同 role 内选 primary —— 先看可信度，再看有效尺寸
        assets.groupBy { it.role }.forEach { (_, group) ->
            group.forEach { it.isPrimary = false }
            group.filter { it.renderable() }
                .maxWithOrNull(
                    compareBy<SiteAssetEntity> { if (it.quality == AssetQuality.TRUSTED) 1 else 0 }
                        .thenBy { it.effectiveSize() }
                )
                ?.isPrimary = true
        }

        return assets
    }

    /**
     * 按展示模式挑出该渲染哪张图。
     *
     * **两种模式的优先级是反的**，这正是"不能用单一 is_primary 全局标记"的原因：
     * - [DisplayMode.TILE]（大图 72px+）要品牌 LOGO 优先，拿不到再退 FAVICON；
     * - [DisplayMode.LIST]（小图 16~24px）要 FAVICON 优先 —— 把 512px 的 LOGO 塞进
     *   16px 的行里既浪费带宽，观感也不比 favicon 好。
     *
     * @param pinnedAssetId 人工钉死的资产，存在即无条件胜出（见 site_display_pref）
     * @return 选中的资产；一张可渲染的都没有时返回 null，调用方应回退到首字母色块
     */
    fun resolve(
        assets: List<SiteAssetEntity>,
        mode: DisplayMode,
        pinnedAssetId: String? = null,
    ): SiteAssetEntity? {
        val usable = assets.filter { it.renderable() }
        if (usable.isEmpty()) return null

        pinnedAssetId?.let { pinned ->
            usable.firstOrNull { it.id == pinned }?.let { return it }
        }

        val roleOrder = when (mode) {
            DisplayMode.TILE -> listOf(AssetRole.LOGO, AssetRole.FAVICON)
            DisplayMode.LIST -> listOf(AssetRole.FAVICON, AssetRole.LOGO)
        }

        // 页面自有图标一旦存在就**整体**接管这个书签，筛选必须发生在按 role 分流**之前**。
        // 放进循环里就变成了"逐 role 接管"：页面往往只拥有其中一个 role —— 只声明了一个
        // `<link rel=icon>` 的深链，那张图会被 assignRoles 的借用逻辑改写成 LOGO，于是它
        // 根本没有 PAGE 层的 FAVICON。此时 LIST 模式先找 FAVICON，页面侧空，就回落到了
        // 站点 favicon —— 渲染出隔壁产品的图标，正是这条链路要防的那个结果。
        //
        // 但必须先收敛到 roleOrder 里的图标角色再筛：SOCIAL/SCREENSHOT 按 ownerTypeOf 恒为
        // PAGE 层，把它们一起喂进去的话，任何一张社交图都会让 preferPageOwned 把整个池子
        // 缩成"只有社交图"，图标一张不剩、这里直接返回 null。
        val pool = preferPageOwned(usable.filter { it.role in roleOrder })

        for (role in roleOrder) {
            val candidates = pool.filter { it.role == role }
            if (candidates.isEmpty()) continue
            val best = when (mode) {
                // 大图要尽量大：先可信度，再尺寸
                DisplayMode.TILE -> candidates.maxWithOrNull(
                    compareBy<SiteAssetEntity> { if (it.quality == AssetQuality.TRUSTED) 1 else 0 }
                        .thenBy { it.effectiveSize() }
                )
                // 小图要"刚好够用"：矢量图最优，其次是最接近 64px 且不小于它的那张
                DisplayMode.LIST -> candidates.minWithOrNull(
                    compareBy<SiteAssetEntity> { if (it.isVector) 0 else 1 }
                        .thenBy { listSizePenalty(it.effectiveSize()) }
                )
            }
            if (best != null) return best
        }
        return null
    }

    /**
     * 挑一张**页面封面**（详情面板顶部那张宽图），与 [resolve] 是两件事。
     *
     * [resolve] 选的是**图标**：方形、几十到几百像素、代表"这个站"。封面选的是**页面长什么样**：
     * 宽幅、代表"这一页"。把 SCREENSHOT 塞进 [resolve] 的 roleOrder 是错的 —— 那会让一张 1280×720
     * 的截图在 TILE 模式下因为"尺寸最大"而胜出，变成书签的图标。
     *
     * 优先级 `SCREENSHOT → SOCIAL`：截图是这一页此刻真实的样子；og:image 是站点**希望**分享时
     * 被看到的样子，往往是通用的品牌banner，同一站点下所有页面共用一张。前者信息量更大，
     * 但也更容易缺失（反爬站点抓不到），所以后者是天然的兜底。
     *
     * 两者都归 [AssetOwnerType.PAGE]（见 [ownerTypeOf]），所以传进来的应当是该书签的 PAGE 层资产。
     *
     * @return 选中的资产；没有可用封面时返回 null，调用方**不应**渲染任何占位
     */
    fun resolveCover(assets: List<SiteAssetEntity>): SiteAssetEntity? {
        val usable = assets.filter { it.renderable() }
        if (usable.isEmpty()) return null
        return COVER_ROLE_ORDER.firstNotNullOfOrNull { role ->
            usable.filter { it.role == role }
                // 同 role 内取最大的一张：封面要铺满一条宽幅，小图放大很难看
                .maxWithOrNull(
                    compareBy<SiteAssetEntity> { if (it.quality == AssetQuality.TRUSTED) 1 else 0 }
                        .thenBy { it.effectiveSize() }
                )
        }
    }

    /**
     * **页面自己的图标整体压过站点图标**，跨 role 一次性决定。
     *
     * 只有 [divergesFromSite] 判定"这一页跟本站点不是一套"时，才会有 PAGE 层的
     * FAVICON/LOGO 行存在（见 `SiteAssetIngestor.assignOwner`）。既然那条判定已经确认过
     * 字节与站点毫无交集，这里就不该再让两者按尺寸/可信度去竞争 —— 站点图标可能又大又
     * TRUSTED，一比就把页面自己的那张挤掉，整条链路白做。
     *
     * **必须在按 role 分流之前调用。** 页面通常只拥有 FAVICON/LOGO 其中之一（见 [resolve]
     * 里的注释），按 role 分别筛选时另一个 role 就会悄悄回落到站点资产。
     *
     * 返回的是**筛选**而不是排序：混在一起排序仍会让站点图标在页面图标不可渲染时悄悄胜出，
     * 而那种情况下宁可走首字母色块，也好过挂一张属于隔壁产品的图标。同理，页面只有 LOGO 时
     * LIST 模式宁可用那张偏大的 LOGO，也不用站点那张尺寸正好但属于别的产品的 favicon。
     */
    private fun preferPageOwned(candidates: List<SiteAssetEntity>): List<SiteAssetEntity> {
        val pageOwned = candidates.filter { it.ownerType == AssetOwnerType.PAGE }
        return pageOwned.ifEmpty { candidates }
    }

    /** 封面的取图顺序，见 [resolveCover]。 */
    private val COVER_ROLE_ORDER = listOf(AssetRole.SCREENSHOT, AssetRole.SOCIAL)

    /**
     * 小图场景下与理想尺寸(64px)的距离：不足 64 的惩罚更重（放大会糊），
     * 超出的只按超出量线性计罚（无非是多传点字节）。
     */
    private fun listSizePenalty(size: Int): Int =
        if (size >= 64) size - 64 else (64 - size) * 4

    /**
     * 大图模式是否应当放弃图片、改用首字母色块。
     *
     * 判据：选中的图要么不可信（只是 favicon 借来充数），要么实际像素撑不到
     * [TILE_MIN_SIZE]。把 32px 的 favicon 拉伸到 72px 观感很差，宁可不用。
     */
    fun shouldFallbackToMonogram(chosen: SiteAssetEntity?): Boolean {
        if (chosen == null) return true
        if (chosen.isVector) return false
        return chosen.quality == AssetQuality.DEGRADED || chosen.effectiveSize() < TILE_MIN_SIZE
    }
}
