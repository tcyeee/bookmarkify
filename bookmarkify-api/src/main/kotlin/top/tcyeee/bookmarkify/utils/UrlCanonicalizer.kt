package top.tcyeee.bookmarkify.utils

/**
 * URL 规范化：把同一个页面的各种写法收敛成同一个 canonical key。
 *
 * 详见根目录 `SITE_LAYERING_DESIGN.md` §4.1。三条必须记住的约束：
 *
 * 1. **只用于「去重 + 抓取目标」，绝不改变用户点击的目标。** 用户点击永远走
 *    `bookmark_user_link.url_full`（原始 URL，带全部参数）。有的链接去掉未知参数就打不开，
 *    以后若有人为了"统一"把点击目标也换成规范化 URL，那就是引入一个只在部分站点上复现的 bug。
 * 2. **追踪参数用黑名单剥离，绝不能用白名单。** `?v=xxx`、`?id=123` 这类业务参数不可枚举，
 *    白名单会把它们一并剥掉，于是两个完全不同的页面共用一条 canonical 记录 —— 正是本次
 *    重构要修的那个 bug。同理，黑名单本身也必须保守：见 [TRACKING_PARAMS] 的注释。
 * 3. **纯函数，无 IO。** 规则会变（新的追踪参数、新的 SPA 路由风格），改这里即可，
 *    不需要动抓取链路。
 */
object UrlCanonicalizer {

    /**
     * 规范化后的 URL 组成部分，对应 `bookmark` 表的三个 key 列。
     *
     * [fragment] 单独成列而不是折进 [path]：`https://a.com/?x=1#/docs?y=2` 这种同时带真 query
     * 和 hash query 的地址，折进去之后无法再拼回正确的顺序（`?` 必须在 `#` 之前，hash 里的
     * `?` 又必须在 `#` 之后）。分三列存，[rawUrl] 的拼接就是无歧义的。
     */
    data class CanonicalParts(
        /** 以 `/` 开头，非根路径不带末尾斜杠 */
        val path: String,
        /** 已剥离追踪参数并按 key 排序；无 query 时为空串（不是 null，省得查询要处理两种"没有"） */
        val query: String,
        /** 仅保留路由型 fragment（`#/…` / `#!…`），锚点型一律丢弃；无则为空串 */
        val fragment: String,
    ) {
        /** 拼回可抓取的地址。注意顺序：query 在 fragment 之前。 */
        fun rawUrl(scheme: String, host: String): String = buildString {
            append(scheme).append("://").append(host).append(path)
            if (query.isNotEmpty()) append('?').append(query)
            if (fragment.isNotEmpty()) append('#').append(fragment)
        }

        /** 是不是站点首页（根路径、无参数、无路由 fragment）。展示策略按这个分叉，见 BookmarkDisplayPolicy。 */
        val isRoot: Boolean get() = path == "/" && query.isEmpty() && fragment.isEmpty()
    }

    /**
     * 精确匹配的追踪参数（小写比较）。
     *
     * **刻意保守。** `from` / `source` / `ref` / `referrer` / `timestamp` / `id` 这些看着像追踪参数
     * 的名字全部**不在**表里：它们在真实站点上经常是业务参数（`?from=2024` 之类），剥错一个就把两个
     * 不同的页面合成了一条记录，这个后果比"多留一个追踪参数导致多一条记录"严重得多 ——
     * 多一条记录只是浪费一次抓取，合错记录是数据错误。
     *
     * 判断标准：这个参数名是否**只**被某个统计/分享渠道使用，改变它不会改变页面内容。
     */
    private val TRACKING_PARAMS: Set<String> = setOf(
        // 广告平台点击 ID
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "ttclid", "twclid",
        "igshid", "igsh", "li_fat_id", "epik", "rdt_cid",
        // 邮件营销
        "mc_cid", "mc_eid", "_hsenc", "_hsmi", "vero_id", "vero_conv", "ck_subscriber_id",
        // 阿里系 / B 站 / 小红书 / 微博的分享埋点
        "spm", "scm", "spm_id_from", "vd_source", "buvid", "is_story_h5",
        "share_source", "share_medium", "share_plat", "share_tag", "share_session_id",
        "xhsshare", "weibo_id",
        // 百度统计
        "hmsr", "hmpl", "hmcu", "hmkw", "hmci",
        // Twitter/X 分享。注意 `?s=` 与 `?t=` 刻意不在表里：WordPress 全站用 `?s=` 做站内搜索，
        // 剥掉就把所有搜索结果页合并成了首页
        "ref_src", "ref_url",
        // 其他公认的（表里一律小写，[isTrackingParam] 会把待判定的 key 转小写后比较）
        "mkt_tok", "trk", "trkcampaign", "sc_cid", "cmpid", "ncid", "wt_mc",
    )

    /** 前缀匹配的追踪参数族（小写比较）。 */
    private val TRACKING_PREFIXES: List<String> = listOf(
        "utm_",      // Google Analytics 全家
        "pk_",       // Piwik
        "matomo_",   // Matomo
        "at_",        // AT Internet
    )

    /**
     * 规范化路径：空 → `/`；非根路径去掉末尾斜杠。
     *
     * 「无路径」和「根路径斜杠」是同一个页面（`https://a.com` 与 `https://a.com/`），
     * 不收敛就会产生两条 canonical 记录。
     */
    fun normalizePath(rawPath: String?): String {
        val path = rawPath?.takeIf { it.isNotBlank() } ?: return "/"
        val withSlash = if (path.startsWith("/")) path else "/$path"
        return if (withSlash.length > 1 && withSlash.endsWith("/")) withSlash.trimEnd('/').ifEmpty { "/" } else withSlash
    }

    /**
     * 规范化 query：剥掉追踪参数 → 按 key 排序 → 重新用 `&` 拼接。
     *
     * 排序是必需的：同一个视频从不同渠道分享出来，参数顺序不同（`?v=A&t=10` vs `?t=10&v=A`），
     * 不排序就会产生两条 canonical 记录、抓两次同一个页面。
     *
     * **不做百分号解码。** 解码再编码会改变字节（`+` 与 `%20`、大小写十六进制），反而制造出新的
     * 不一致；这里只按 `&` / `=` 切分，参数值原样保留。
     */
    fun normalizeQuery(rawQuery: String?): String {
        val query = rawQuery?.takeIf { it.isNotBlank() } ?: return ""
        return query.split('&')
            .filter { it.isNotEmpty() }
            .map { pair -> pair.substringBefore('=') to pair }
            .filterNot { (key, _) -> isTrackingParam(key) }
            // 同名参数（`?tag=a&tag=b`）按取值一并排序，保证顺序稳定；这类参数多为多选，顺序无语义
            .sortedWith(compareBy({ it.first.lowercase() }, { it.second }))
            .joinToString("&") { it.second }
    }

    /**
     * 规范化 fragment：**只保留路由型**，锚点型丢弃。
     *
     * - `#/docs/intro`、`#!/inbox` → 路由型，是页面身份的一部分。hash 路由的老 SPA（部分
     *   Notion 镜像、老 Vue/Angular 站）整站只有一个 `/` 路径，丢了 fragment 会让整站
     *   collapse 成一条 canonical 记录、全部共用同一份标题和图标。
     * - `#comments`、`#L42` → 页内锚点，指向同一个文档，必须丢掉，否则同一页面的不同锚点
     *   会各占一条记录、各抓一次。
     *
     * 路由型内部的 query 同样要排序，所以这里递归复用 [normalizeQuery]。
     */
    fun normalizeFragment(rawFragment: String?): String {
        val fragment = rawFragment?.takeIf { it.isNotBlank() } ?: return ""
        // `#!` 是 Google 早年的 hashbang 约定，等价于 `#/`；剥掉 `!` 后按同一套规则处理
        val route = fragment.removePrefix("!")
        if (!route.startsWith("/")) return ""
        val path = normalizePath(route.substringBefore('?'))
        val query = normalizeQuery(route.substringAfter('?', ""))
        return if (query.isEmpty()) path else "$path?$query"
    }

    /** 三个部分一次算完。 */
    fun canonicalize(rawPath: String?, rawQuery: String?, rawFragment: String?) = CanonicalParts(
        path = normalizePath(rawPath),
        query = normalizeQuery(rawQuery),
        fragment = normalizeFragment(rawFragment),
    )

    private fun isTrackingParam(key: String): Boolean {
        val lower = key.lowercase()
        return lower in TRACKING_PARAMS || TRACKING_PREFIXES.any { lower.startsWith(it) }
    }
}
