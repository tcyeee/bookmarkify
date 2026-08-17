package top.tcyeee.bookmarkify.server.asset

import org.springframework.stereotype.Service

/**
 * 「这一**页**长什么样」—— 详情面板顶部那张宽图。
 *
 * **与 [IconResolver] 是两件事。** 那个选图标：方形、几十到几百像素、代表「这个站」；
 * 这个选封面：宽幅、代表「这一页」。根 `CLAUDE.md` 早就写着「两者不能混」，但在
 * 2026-08-17 拆分之前它们住在同一个类里，只靠注释拦着。混淆的后果是具体的：把 SCREENSHOT
 * 加进图标的候选池，一张 1280×720 的截图会因为「尺寸最大」在 TILE 模式下胜出，变成书签图标；
 * 反过来用图标那套等宽高的 `m_fill` 去签封面，会把宽幅截图裁成正方形。
 *
 * 只查 PAGE 层：封面的两个来源（SCREENSHOT / SOCIAL）都归页面，不需要 `page → site` 那一跳。
 */
@Service
class CoverResolver(
    private val query: SiteAssetQuery,
) {

    /**
     * 批量解析封面。选取规则见 [AssetRolePolicy.resolveCover]。
     *
     * @return 只含**有封面**的书签；没有的键直接缺席，让调用方 `?:` 成 null 而不是空串
     */
    fun resolveBatch(pageIds: List<String>): Map<String, String> {
        val ids = pageIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val pageAssets = query.pageAssetsOfBatch(ids)
        // 必须批量取账本行，否则详情列表就是 N+1
        val objectByFileId = query.objectsOf(pageAssets.values.flatten())

        return ids.mapNotNull { id ->
            val chosen = AssetRolePolicy.resolveCover(pageAssets[id].orEmpty()) ?: return@mapNotNull null
            val url = AssetUrlSigner.signedCover(chosen, objectByFileId) ?: return@mapNotNull null
            id to url
        }.toMap()
    }

    /** 单个书签的封面。列表场景请用 [resolveBatch]，避免 N+1。 */
    fun resolveOne(pageId: String): String? = resolveBatch(listOf(pageId))[pageId]
}
