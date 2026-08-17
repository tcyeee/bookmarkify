package top.tcyeee.bookmarkify.entity.enums

/**
 * 图片的**用途**。
 *
 * 与 scrapper 报告的 `extractor`（出处，事实）严格区分：出处是网页的客观属性，用途是
 * 书签鸭的主观判断。二者的映射规则集中在
 * [AssetRolePolicy][top.tcyeee.bookmarkify.server.asset.AssetRolePolicy]，
 * 改判定规则只需改那张表，不必动 scrapper，也不必重抓。
 */
enum class AssetRole {
    /** 浏览器标签页前那个小图标 */
    FAVICON,

    /** 品牌 LOGO */
    LOGO,

    /** 社交分享图（长方形，约 1200x630）。注意 "OG" 是协议名不是类型名，故不用它命名 */
    SOCIAL,

    /** 无头浏览器渲染的页面截图 */
    SCREENSHOT,
}

/**
 * 资产挂在哪一层。
 *
 * 分界线不是新造的概念，就是 [AssetRole] 本身 —— 换同域下另一个页面，这张图会变吗？
 *
 * | role | 归属 | 理由 |
 * |---|---|---|
 * | [AssetRole.FAVICON] / [AssetRole.LOGO] | [SITE] | 全站一套，同域所有页面共享 |
 * | [AssetRole.SOCIAL] / [AssetRole.SCREENSHOT] | [PAGE] | 每页不同，就是页面内容本身 |
 *
 * 这个分层是本次改造省下大头开销的地方：同域名 1000 个页面此前要各存一份 favicon、
 * 各下载上传一次、管理员各调一次内边距，现在都是每域名一次。
 * 详见根目录 `SITE_LAYERING_DESIGN.md` §5。
 */
enum class AssetOwnerType {
    /** 归属 `site.id`：全站共享的图标 */
    SITE,

    /** 归属 `bookmark.id`：只属于这一个页面的图 */
    PAGE,
}

/**
 * 资产的可信度分级，同样由 `extractor` 推导。
 *
 * 关键用途：`APPLE_TOUCH_ICON` / `LINK_ICON` 被映射成 [AssetRole.LOGO] 时只是**降级候选**
 * ——它们本质上是 favicon，不是品牌 LOGO。大图场景拿到 [DEGRADED] 的 LOGO 时，应当走
 * 首字母色块，而不是把 32px 的图拉伸到 72px（后者观感很差）。
 */
enum class AssetQuality {
    /** 语义明确：JSON-LD Organization.logo、manifest icons、og:image 等 */
    TRUSTED,

    /** 借用其它用途的图凑数，仅作兜底 */
    DEGRADED,
}

/**
 * 书签的展示模式。
 *
 * 两种模式对资产和文案的需求**恰好相反**，因此不能用单一的"主图"标记：
 *
 * | | [TILE] | [LIST] |
 * |---|---|---|
 * | 形态 | 大图 + 下方短名 | 小图 + 右侧全名 |
 * | 图片优先级 | LOGO 优先 | FAVICON 优先 |
 * | 文案 | site_short_name | title |
 * | 内边距/背景色 | 生效 | 不生效（16px 下几乎不可见） |
 *
 * 这个二分与 Web App Manifest 的 `name` / `short_name` 之分是同一件事 —— W3C 定义后者
 * 正是为了"主屏图标下方空间受限"这个场景。
 */
enum class DisplayMode {
    /** 大图 + 下方短名称 */
    TILE,

    /** 小图 + 右侧完整名称 */
    LIST,
}

/**
 * 某个站点在 [DisplayMode.TILE] 下**规则实际给出的结论**，后台图标判定总览按它分档。
 *
 * 四个取值刻意把「走了色块」拆成两种，因为它们的成因完全不同、修法也完全不同：
 * [MONOGRAM_QUALITY] 是出处判断否决了一张够大的图（规则的事，改代码就能修），
 * [MONOGRAM_SIZE] 是站点确实没提供大图（数据的事，改代码修不了）。
 * 合并成一个「显示色块」只会让每次规则改动都量不出效果 —— 而量效果正是这张表存在的唯一理由。
 *
 * 取值由 [AssetRolePolicy][top.tcyeee.bookmarkify.server.asset.AssetRolePolicy] 现算，
 * 不落库：它是判定的**输出**，存下来就又造出一份需要跟着规则一起刷新的物化数据。
 */
enum class IconVerdict {
    /** 正常显示图片 */
    IMAGE,

    /**
     * 选中的图 ≥ TILE_MIN_SIZE，却仍然退回了首字母色块 —— 即**尺寸之外还有别的否决权**。
     *
     * **2026-08-17 起恒为 0**：当时唯一那条与尺寸无关的判据（`quality == DEGRADED`）已从
     * `shouldFallbackToMonogram` 移除，那一次改动把生产的正常显示率从 17% 抬到了 41%。
     * 这一档留着不是历史包袱，是**回归探测器**：它再次非零，就说明有人又往那个函数里加了
     * 一条与「放大会不会糊」无关的判据。
     */
    MONOGRAM_QUALITY,

    /** 选中的图确实太小，放大会糊，走首字母色块 */
    MONOGRAM_SIZE,

    /** 一张可渲染的图标都没有 */
    NO_ASSET,
}
