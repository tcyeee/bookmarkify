package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.SiteEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.mapper.PageMapper
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import top.tcyeee.bookmarkify.mapper.SiteMapper
import top.tcyeee.bookmarkify.server.IOssObjectService

/**
 * `site_asset` 的**取数层**：只负责把行读出来，不做任何判定、不签任何地址。
 *
 * ## 为什么单独一层
 *
 * 这几个查询同时被三类消费者需要 —— 选图标（[IconResolver]）、选封面（[CoverResolver]）、
 * 后台排查（原始资产列表）。它们要的**判定**完全不同，要的**数据**却是同一批行。
 * 混在一个类里的直接后果，是 `objectsOf` 这个纯取数工具被 `SiteServiceImpl` 和
 * `BookmarkAdminService` 伸手进图标解析器去拿 —— 那两处根本不关心图标。
 *
 * ## 查询一律批量
 *
 * 这里的主要消费者都是列表场景（一屏几十个图标、后台一页几十行），任何「用的时候再查」
 * 的写法都会退化成 N+1。少数真正单条的调用点有 [assetsOf] 这样的薄包装，但它只是
 * `listOf(x)` 的语法糖 —— 不要在循环里调它。
 */
@Service
class SiteAssetQuery(
    private val siteAssetMapper: SiteAssetMapper,
    private val pageMapper: PageMapper,
    private val siteMapper: SiteMapper,
    private val ossObjectService: IOssObjectService,
) {

    /**
     * 每个页面**可用的全部资产** = 它所属站点的图标 + 它自己的社交图/截图。
     *
     * 站点图标只查一次、按 siteId 分组后共享给同站点的多个页面，这正是分层省下来的开销：
     * 一屏 20 个 YouTube 视频此前要读 20 份一模一样的 favicon 行。
     */
    fun assetsOfBatch(pageIds: List<String>): Map<String, List<SiteAssetEntity>> {
        val ids = pageIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()

        val siteIdOf = siteIdOf(ids)
        val siteIds = siteIdOf.values.distinct()

        val siteAssets = if (siteIds.isEmpty()) emptyMap() else siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, AssetOwnerType.SITE)
                .`in`(SiteAssetEntity::ownerId, siteIds)
                .orderByAsc(SiteAssetEntity::role)
                .orderByDesc(SiteAssetEntity::isPrimary)
        ).groupBy { it.ownerId }

        val pageAssets = pageAssetsOfBatch(ids)

        return ids.associateWith { id ->
            siteAssets[siteIdOf[id]].orEmpty() + pageAssets[id].orEmpty()
        }
    }

    /** 单个页面的全部可用资产。**列表场景必须用 [assetsOfBatch]** —— 逐行调这个就是 N+1 */
    fun assetsOf(pageId: String): List<SiteAssetEntity> =
        assetsOfBatch(listOf(pageId))[pageId].orEmpty()

    /**
     * 只取 [AssetOwnerType.PAGE] 层的资产。封面专用 —— 它的两个来源（截图 / 社交图）都归页面，
     * 不需要 `page → site` 那一跳。
     */
    fun pageAssetsOfBatch(pageIds: List<String>): Map<String, List<SiteAssetEntity>> {
        val ids = pageIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, AssetOwnerType.PAGE)
                .`in`(SiteAssetEntity::ownerId, ids)
                .orderByAsc(SiteAssetEntity::role)
                .orderByDesc(SiteAssetEntity::isPrimary)
        ).groupBy { it.ownerId }
    }

    /**
     * 按**域名**取站点图标 —— 全类唯一不以 pageId 为入口的查询。
     *
     * 存在的理由是调用方手上真的只有一个域名字符串：`scrapper_call_log` 记的是一次抓取动作，
     * 抓失败时压根不存在对应的 page 行，自然也没有 pageId。此前后台就是因为没有这个入口，
     * 在前端拼了 `https://<host>/favicon.ico` 直连外站。
     *
     * 只查 SITE 层：域名能确定的就到站点为止，PAGE 层资产属于某个具体页面，
     * 用它来代表整个域名是错的。
     */
    fun siteIconsByHost(hosts: Collection<String>): Map<String, List<SiteAssetEntity>> {
        val wanted = hosts.filter { it.isNotBlank() }.distinct()
        if (wanted.isEmpty()) return emptyMap()

        val siteIdByHost = siteMapper.selectMaps(
            KtQueryWrapper(SiteEntity::class.java)
                .select(SiteEntity::id, SiteEntity::host)
                .`in`(SiteEntity::host, wanted)
        ).mapNotNull { row ->
            val id = row.column("id")
            val host = row.column("host")
            if (id.isNullOrBlank() || host.isNullOrBlank()) null else host to id
        }.toMap()
        if (siteIdByHost.isEmpty()) return emptyMap()

        val assetsBySite = siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, AssetOwnerType.SITE)
                .`in`(SiteAssetEntity::ownerId, siteIdByHost.values.distinct())
                .`in`(SiteAssetEntity::role, listOf(AssetRole.FAVICON, AssetRole.LOGO))
        ).groupBy { it.ownerId }

        return siteIdByHost.mapNotNull { (host, siteId) ->
            assetsBySite[siteId]?.let { host to it }
        }.toMap()
    }

    /**
     * 一批资产的 `file_id` → 账本行。
     *
     * 对外是一个显式的 Map 而不是让签名那一层自己去查，是为了逼着调用方在批量入口处就把这次
     * 查询做掉：首页一屏几十个图标，逐张查库就是教科书式的 N+1。
     */
    fun objectsOf(assets: Collection<SiteAssetEntity>): Map<String, OssObjectEntity> {
        val fileIds = assets.mapNotNull { it.fileId?.takeIf { id -> id.isNotBlank() } }.distinct()
        if (fileIds.isEmpty()) return emptyMap()
        return ossObjectService.findByIds(fileIds)
    }

    /**
     * pageId → siteId。一条 in 查询，只取需要的两列。
     *
     * 用 `selectMaps` 而不是把两列映射回 [PageEntity]：实体是 Kotlin data class，
     * `id`/`urlHost`/`urlScheme` 没有默认值 ⇒ 没有无参构造，MyBatis 只能退化成
     * 「按结果列去找同签名的构造函数」，两列 String 匹配不上任何一个，运行时直接抛
     * `No constructor found ... matching [String, String]`。**投影查询一律走
     * selectMaps/selectObjs，别拿实体接残缺的列。**
     */
    private fun siteIdOf(pageIds: List<String>): Map<String, String> = pageMapper.selectMaps(
        KtQueryWrapper(PageEntity::class.java)
            .select(PageEntity::id, PageEntity::siteId)
            .`in`(PageEntity::id, pageIds)
    ).mapNotNull { row ->
        val id = row.column("id")
        val siteId = row.column("site_id")
        if (id.isNullOrBlank() || siteId.isNullOrBlank()) null else id to siteId
    }.toMap()

    /**
     * Map 结果的 key 取自结果集列名（`id` / `site_id`）。这里仍按「忽略大小写与下划线」去匹配：
     * 认错一列的后果是整批解析静默退化成首字母色块，而不是报错，不值得赌驱动/配置的大小写行为。
     */
    private fun Map<String, Any?>.column(name: String): String? {
        val key = name.replace("_", "").lowercase()
        return entries.firstOrNull { it.key.replace("_", "").lowercase() == key }?.value?.toString()
    }
}
