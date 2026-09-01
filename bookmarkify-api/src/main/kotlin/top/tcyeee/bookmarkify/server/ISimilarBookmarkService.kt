package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.SimilarBookmarksVO

/**
 * 「更多相似书签」。用户在某条书签上点开 `/bookmark/similar` 时的数据来源。
 *
 * 两路合并：
 * 1. **本地库** —— 与本页共享 `category` 的其它站点首页，即时、零外部成本；
 * 2. **DeepSeek 推荐** —— `IApiService.inferSimilarSites` 给出域名，再走**和加书签完全相同的
 *    抓取链路**收录进库（图标落 OSS、`page_meta`、AI 归类、截图），幻觉域名抓不到正文即丢弃。
 *    结果按 `site` 缓存在 `site_similar_item`，TTL 60 天。
 *
 * 从 `BookmarkAdminService` 拆出的 `ingestOne` 归这里：后台「一键收录」与用户端冷计算是同一件事。
 */
interface ISimilarBookmarkService {

    /**
     * 某条书签的「更多相似书签」。本地相似即时返回；AI 推荐命中缓存则一并返回，未命中则触发
     * 后台冷计算（受单用户每日预算限制）并把 [SimilarBookmarksVO.computing] 置 true。
     */
    fun similarFor(pageId: String, uid: String): SimilarBookmarksVO

    /**
     * 收录单个相似站点域名：走加书签同一条链路（抓取 + 图标 + `page_meta` + AI 归类 + 截图）。
     * 本地已有 → `EXISTS`；抓到正文 → `INGESTED`；抓不到（幻觉/失效域名）→ 删除记录并 `SKIPPED`。
     * 供后台「一键收录」与用户端冷计算共用。
     */
    fun ingestOne(domain: String): String

    /**
     * 对某站点执行一次冷计算：DeepSeek 推荐 → 整组重写 `site_similar_item` → 逐个 [ingestOne]。
     * **只经 `SimilarColdComputeEvent` 异步调用**，不要在请求线程里直接调（耗时几十秒到几分钟）。
     */
    fun runColdCompute(siteId: String, title: String?, description: String?, host: String)
}
