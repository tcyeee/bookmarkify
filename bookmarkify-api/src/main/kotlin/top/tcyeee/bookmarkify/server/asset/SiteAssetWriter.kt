package top.tcyeee.bookmarkify.server.asset

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import top.tcyeee.bookmarkify.config.entity.ScrapperConfig
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponse
import top.tcyeee.bookmarkify.entity.entity.SiteAssetEntity
import top.tcyeee.bookmarkify.entity.entity.PageMetaEntity
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.OssAddressing
import top.tcyeee.bookmarkify.entity.enums.OssObjectSource
import top.tcyeee.bookmarkify.mapper.ScrapeSnapshotMapper
import top.tcyeee.bookmarkify.mapper.SiteAssetMapper
import top.tcyeee.bookmarkify.mapper.PageMetaMapper
import top.tcyeee.bookmarkify.server.IOssObjectService
import top.tcyeee.bookmarkify.server.OssObjectSpec
import top.tcyeee.bookmarkify.utils.OssUtils

/**
 * 把一次抓取结果落库。
 *
 * 只写 `scrape_snapshot` / `site_page_meta` / `site_asset` 三张表 —— 全是**抓取事实**。
 * 旧的 `bookmark_logo` 把抓取事实、文件元数据与人工偏好混在一行，于是每次重抓都得做小心翼翼的
 * 部分更新才不至于冲掉管理员的设置。人工偏好那张表（`site_display_pref`）已于 2026-08-17 移除，
 * 但这条「本类只写抓取事实」的边界不随之作废：它同样是 `site_display_pref` 当初能被干净删掉的原因。
 */
@Service
class SiteAssetWriter(
    private val scrapeSnapshotMapper: ScrapeSnapshotMapper,
    private val sitePageMetaMapper: PageMetaMapper,
    private val siteAssetMapper: SiteAssetMapper,
    private val objectMapper: ObjectMapper,
    private val scrapperConfig: ScrapperConfig,
    private val ossObjectService: IOssObjectService,
) {

    /**
     * 落一次成功抓取。
     *
     * 资产按**归属分别处理**，两层的写入强度刻意不同：
     *
     * | 归属 | 写入语义 | 为什么 |
     * |---|---|---|
     * | PAGE（社交图/截图） | 整体替换 | 就是这个页面的内容，页面改版后旧图多半已 404 |
     * | SITE（favicon/logo），抓的是首页 | 整体替换 | 首页是站点图标的权威来源 |
     * | SITE，抓的是深链 | **只补齐缺失的，绝不删** | 见下 |
     *
     * 还有一条例外：深链声明的图标若与站点现有图标**字节毫无交集**，说明这个域名下塞了
     * 多个互不相关的产品（`tools.example.com/tools/a` 与 `/tools/b`），那批图标改判给
     * PAGE 层，走上面第一行的整体替换。判定见 [AssetRolePolicy.divergesFromSite]。
     *
     * 最后一条是这个方法里唯一不显然的地方。站点图标现在是同域所有页面共享的一份，如果任何
     * 一个深链抓取都能整体替换它，那么同一站点两个页面的抓取会互相覆盖 —— 而
     * `ParseLock` 是**按 bookmark** 加的，根本挡不住这种跨页面的竞争（两把锁，两个不同的 id）。
     * 更糟的是深链页面声明的图标常常还不如首页全（很多站只在首页放 manifest）。
     *
     * 把「整体替换」这唯一的破坏性操作收窄到首页那一条记录上，竞争就重新落回单条 bookmark
     * 的锁里了，不需要再引入一把站点级锁。
     *
     * @param isRootPage 这次抓的是不是站点首页（`bookmark.isRootPage`）
     */
    @Transactional(rollbackFor = [Exception::class])
    fun persist(
        siteId: String,
        pageId: String,
        url: String,
        response: ScrapeResponse,
        durationMs: Int,
        isRootPage: Boolean,
    ) {
        // 深链才需要判"这一页是不是同域下的另一个产品"，判据是它声明的图标与站点现有图标
        // 的字节交集 —— 所以得先把站点现有图标取出来。首页跳过这次查询：它就是站点本身。
        val existingSiteIcons = if (isRootPage) emptyList() else assetsOf(AssetOwnerType.SITE, siteId)

        val p = SiteAssetIngestor.project(
            siteId, pageId, url, response, durationMs, objectMapper,
            isRootPage = isRootPage,
            existingSiteIcons = existingSiteIcons,
        )

        registerObjects(p.assets)

        scrapeSnapshotMapper.insert(p.snapshot)

        p.pageMeta?.let { meta ->
            val exists = sitePageMetaMapper.selectById(pageId) != null
            if (exists) sitePageMetaMapper.updateById(meta) else sitePageMetaMapper.insert(meta)
        }

        val (siteAssets, pageAssets) = p.assets.partition { it.ownerType == AssetOwnerType.SITE }

        dropStalePageIcons(pageId, pageAssets)
        replaceAssets(AssetOwnerType.PAGE, pageId, pageAssets)
        if (isRootPage) {
            replaceAssets(AssetOwnerType.SITE, siteId, siteAssets)
        } else {
            fillMissingAssets(siteId, siteAssets)
        }

        log.debug(
            "[SiteAssetWriter] 落库完成: pageId={}, siteId={}, isRootPage={}, siteAssets={}, pageAssets={}, hasMeta={}",
            pageId, siteId, isRootPage, siteAssets.size, pageAssets.size, p.pageMeta != null
        )
    }

    /**
     * 把本次抓取真正落进桶里的对象记入 `oss_object`（见 `FILE-SYSTEM-REFACTOR.md` P1）。
     *
     * **记的是"scrapper 往桶里写了什么"，不是"库里留下了哪几行"**，所以它放在投影之后、任何
     * 落库分支之前 —— 走整体替换、走深链补齐、还是被 [isIdenticalToExisting] 整批跳过，对象都
     * 已经实实在在地 PUT 进去了，账本必须照记。
     *
     * 同理，[IOssObjectService.register] 用的是独立事务：抓取事务回滚撤销不了 scrapper 在事务
     * 之外完成的上传，那笔账也就不该跟着回滚 —— 否则就正好漏掉"落库失败留下的孤儿对象"这一类，
     * 而那恰恰是账本最需要抓住的东西。
     *
     * 寻址方式由 [addressingOf] 按 key 的实际形态判定，不再按 role 推断。
     */
    private fun registerObjects(assets: List<SiteAssetEntity>) {
        val specs = assets.mapNotNull { a ->
            a.storageUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { key ->
                OssObjectSpec(
                    objectKey = key,
                    source = OssObjectSource.SCRAPPER,
                    addressing = addressingOf(key, a.role, a.contentHash),
                    contentHash = a.contentHash,
                    size = a.byteSize,
                    mime = a.mime,
                    width = a.width,
                    height = a.height,
                    isVector = a.isVector,
                )
            }
        }
        if (specs.isEmpty()) return

        // 把账本行ID盖回资产行。入账按 key 幂等，所以同一个 key 每次都拿到同一个 id ——
        // 这正是 isIdenticalToExisting 能继续认出"站点没改版"的前提。
        val idByKey = ossObjectService.registerAll(specs)
        assets.forEach { a ->
            a.storageUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { key -> a.fileId = idByKey[key] }
        }
    }

    /**
     * 判定一个 object key 是怎么算出来的。
     *
     * **依据是 key 的实际形态，不是 role。** 原先按 role 推断（非截图一律 [OssAddressing.CONTENT]）
     * 在 scrapper 升级前后混存的数据上会直接说谎：升级前写入的 key 是 `sha256(源URL).<ext>`，
     * 与字节无关、重抓会被覆盖，却同样被标成 CONTENT。
     *
     * 这不只是标签错。[OssObjectEntity.immutable] 就是从这一列推出来的，
     * [OssUtils.signAsset] 据此签 [OssUtils.IMMUTABLE_TTL_MILLIS]（24h）的长效链接 ——
     * 于是一批随时可能被覆盖的对象以"字节永不改变"的名义拿到了长效 URL，
     * 站点换图之后最长 24 小时都是旧图，且重抓也修不好。
     *
     * 判据来自 scrapper 的 `oss.rs::asset_key`：内容寻址的 key 末段就是 `content_hash` 的
     * hex 本身、且不带扩展名。对不上就不敢当成不可变 —— 多签几次短链接的代价，
     * 远小于把一张会变的图当成永不改变。
     */
    private fun addressingOf(key: String, role: AssetRole, contentHash: String?): OssAddressing {
        // 截图刻意保留 URL 寻址：每次抓的字节都不同，内容寻址等于无上界增长，而去重收益恰好是零
        if (role == AssetRole.SCREENSHOT) return OssAddressing.SOURCE_URL
        // 没有哈希就无从判定形态（PROBE 未下载、或改造前写入的行），如实标成"形态不明"
        val hex = contentHash?.substringAfterLast(':')?.trim()?.takeIf { it.isNotEmpty() }
            ?: return OssAddressing.LEGACY
        return if (key.substringAfterLast('/').equals(hex, ignoreCase = true)) {
            OssAddressing.CONTENT
        } else {
            OssAddressing.SOURCE_URL
        }
    }

    /**
     * 只写入那一行 SCREENSHOT 资产，其余一概不碰。
     *
     * 截图是**另一趟抓取**的产物（见 `BookmarkScreenshotEvent`），不能复用 [persist]：
     * 那条路会走 `replaceAssets(PAGE, …)` 整体替换，而 SOCIAL（og:image）同样归属 PAGE，
     * 于是补一张封面的副作用是把主抓取刚写好的社交图全删了。这里按 (归属, role) 精确定位，
     * 删旧的那一行、插新的那一行。
     *
     * 截图 key 按页面 URL 寻址、自我覆盖（见 `oss.rs::screenshot_key`），所以同一页面反复
     * 补抓不会在桶里堆积；但格式变更会换扩展名，旧 key 因此仍要走一次孤儿回收。
     *
     * @return 是否真的写进去了
     */
    @Transactional(rollbackFor = [Exception::class])
    fun upsertScreenshot(pageId: String, url: String, response: ScrapeResponse): Boolean {
        if (pageId.isBlank()) return false
        val shot = SiteAssetIngestor.screenshotAsset(pageId, url, response)
            ?: return false

        registerObjects(listOf(shot))

        val existing = assetsOf(AssetOwnerType.PAGE, pageId)
            .filter { it.role == AssetRole.SCREENSHOT }

        // 截图 key 是**页面 URL** 的哈希（见 `oss.rs::screenshot_key`），同一页面每次补抓都是同一个
        // key、桶里的字节却被覆盖了。只比 key 等于"第一次之后永远跳过"，行上的 width/height/
        // byte_size/mime 会一直停在第一次的值，而它们描述的对象早就换过内容了。
        // 按整行比：key 没变但尺寸/体积/格式变了，照样要把那行重写成实际的样子。
        if (isIdenticalToExisting(existing, listOf(shot))) {
            log.debug("[SiteAssetWriter] 截图与库中一致，跳过: pageId={}", pageId)
            return false
        }

        val previousKeys = existing.mapNotNull { it.storageUrl?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        existing.forEach { siteAssetMapper.deleteById(it.id) }
        siteAssetMapper.insert(shot)

        scheduleOrphanCleanup(pageId, previousKeys - setOfNotNull(shot.storageUrl))
        log.debug("[SiteAssetWriter] 截图落库: pageId={}, key={}", pageId, shot.storageUrl)
        return true
    }

    /**
     * 整体替换某个归属下的资产：先删旧的再写新的。
     *
     * 增量合并没有意义 —— 页面改版后旧图标可能已经 404，留着只会让选取策略挑到死链。
     */
    /**
     * 本次抓取判定这一页**不再与站点发散**时，删掉上一轮留下的 PAGE 层图标。
     *
     * 为什么不能交给 [replaceAssets]：它的 `projected.isEmpty()` 守卫是按「抓取偶发少返回
     * 一次不该让图片凭空消失」写的，那条理由对 SOCIAL/SCREENSHOT 成立 —— 站点某次没吐
     * `og:image`，留着上一张是更好的降级。但对 PAGE 层图标不成立：图标为空不是"这次没抓到"，
     * 而是 [AssetRolePolicy.divergesFromSite] 给出的一个**肯定结论** ——「这一页现在和站点
     * 是同一套图标」。两种语义共用一个守卫，后果是页面只要发散过一次，那批 PAGE 图标就
     * 再也删不掉：本次 `pageAssets` 只剩 SOCIAL，而页面若连 `og:image` 都没有就是空集，
     * 守卫直接 early-return。此后 [AssetRolePolicy.preferPageOwned] 会一直优先那批陈旧图标，
     * 连站点后来正常换图都盖不过去。
     *
     * 只删 FAVICON/LOGO，绝不碰 SOCIAL/SCREENSHOT —— 那条守卫对它们是对的。
     */
    private fun dropStalePageIcons(pageId: String, projectedPageAssets: List<SiteAssetEntity>) {
        if (pageId.isBlank()) return
        // 本次仍然发散：图标交给 replaceAssets 的整体替换，这里不插手
        if (projectedPageAssets.any { it.role in ICON_ROLES }) return

        val stale = assetsOf(AssetOwnerType.PAGE, pageId).filter { it.role in ICON_ROLES }
        if (stale.isEmpty()) return

        siteAssetMapper.delete(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, AssetOwnerType.PAGE)
                .eq(SiteAssetEntity::ownerId, pageId)
                .`in`(SiteAssetEntity::role, ICON_ROLES)
        )
        log.info(
            "[SiteAssetWriter] 该页已不再与站点发散，清理页面级图标: pageId={}, removed={}",
            pageId, stale.size
        )
        // 与整体替换一致：这批行没了之后对应的 OSS 对象可能失去最后一个引用者
        scheduleOrphanCleanup(pageId, stale.mapNotNull { it.storageUrl?.trim()?.takeIf(String::isNotEmpty) }.toSet())
    }

    private fun replaceAssets(ownerType: AssetOwnerType, ownerId: String, projected: List<SiteAssetEntity>) {
        if (ownerId.isBlank()) return
        val existing = assetsOf(ownerType, ownerId)

        // 内容定期重抓开启后，绝大多数重抓的结果与库里**完全一样**（站点没改版）。
        // 照旧走一遍"删全部 + 插全部"只是白写一堆行、白调一次孤儿回收，还把 id 和
        // fetched_at 全换一遍，让"这张图什么时候第一次见到"这个信息凭空丢失。
        if (isIdenticalToExisting(existing, projected)) {
            log.debug(
                "[SiteAssetWriter] 资产与库中完全一致，跳过整体替换: ownerType={}, ownerId={}, assets={}",
                ownerType, ownerId, projected.size
            )
            return
        }
        // 本次没抓到任何这一层的资产时也不要清空：抓取偶发少返回一次不该让站点图标凭空消失，
        // 留着上一次的结果是更好的降级。真正的失效由「整体替换」在有新数据时完成。
        if (projected.isEmpty()) {
            log.debug(
                "[SiteAssetWriter] 本次未抓到该层资产，保留库中现值: ownerType={}, ownerId={}, existing={}",
                ownerType, ownerId, existing.size
            )
            return
        }

        // 整体替换会让上一轮的 OSS 对象失去最后一个引用者，得记下来事后回收
        val previousKeys = existing.mapNotNull { it.storageUrl?.trim()?.takeIf(String::isNotEmpty) }.toSet()

        siteAssetMapper.delete(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, ownerType)
                .eq(SiteAssetEntity::ownerId, ownerId)
        )
        projected.forEach { siteAssetMapper.insert(it) }

        val currentKeys = projected.mapNotNull { it.storageUrl?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        scheduleOrphanCleanup(ownerId, previousKeys - currentKeys)
    }

    /**
     * 深链抓取对站点图标的贡献：**只插入库里还没有的那些，一条都不删**。
     *
     * 站点已经有图标了就整批跳过 —— 深链页面声明的图标通常不如首页全（manifest 往往只挂在
     * 首页），拿它去补充一个已经完整的图标集只会引入一堆降级候选。只有在站点侧一张图都没有
     * 时才有补齐的价值，比如用户添加的第一条书签就是深链、首页从未被抓过。
     *
     * 「站点侧一张图都没有」这个前置检查与插入之间存在一个窄窗口：若另一次抓取正好在这中间提交，
     * 插入会撞上 `idx_site_asset_unique`。**这里刻意不捕获那个异常** —— 在 PostgreSQL 里事务内
     * 一旦触发约束冲突，整个事务就进入 aborted 状态，`runCatching` 之后的语句照样全部失败，
     * 捕获只会把"事务已经废了"伪装成"已跳过"。让它照常回滚：本次抓取的写入丢掉，而对账任务
     * 会重新抓一次，届时站点图标已由对手补齐，走的就是上面那条 early return。
     */
    private fun fillMissingAssets(siteId: String, projected: List<SiteAssetEntity>) {
        if (siteId.isBlank() || projected.isEmpty()) return
        if (assetsOf(AssetOwnerType.SITE, siteId).isNotEmpty()) return

        projected.forEach { siteAssetMapper.insert(it) }
        log.debug("[SiteAssetWriter] 深链抓取补齐了站点图标: siteId={}, assets={}", siteId, projected.size)
    }

    /**
     * 本次投影出的资产是否与库中现存的完全等价。
     *
     * 比较的是**全部由抓取与策略推导出来的列**，不只是 `content_hash`。只比哈希会破坏
     * 「改 [AssetRolePolicy] 的规则后重新抓一遍即可生效、无需改 scrapper」这条性质：
     * 规则改了而图片没变时，哈希一样但 role/quality/isPrimary 已经不同，必须照常重写。
     *
     * 不参与比较的只有 `id`（每次投影都新生成）和 `fetchedAt`（本次抓取时间，本就该保留旧值）。
     */
    private fun isIdenticalToExisting(existing: List<SiteAssetEntity>, projected: List<SiteAssetEntity>): Boolean {
        if (existing.size != projected.size || existing.isEmpty()) return false
        fun SiteAssetEntity.identity() = listOf(
            role, extractor, quality, originUrl, resolvedUrl, storageUrl,
            width, height, byteSize, mime, isVector, contentHash, isPrimary, errorMsg,
        )
        return existing.map { it.identity() }.sortedBy { it.toString() } ==
            projected.map { it.identity() }.sortedBy { it.toString() }
    }

    /** 某个归属下当前的全部资产行。 */
    private fun assetsOf(ownerType: AssetOwnerType, ownerId: String): List<SiteAssetEntity> =
        siteAssetMapper.selectList(
            KtQueryWrapper(SiteAssetEntity::class.java)
                .eq(SiteAssetEntity::ownerType, ownerType)
                .eq(SiteAssetEntity::ownerId, ownerId)
        )

    /**
     * 回收本次重抓后彻底没人引用的 OSS 对象。
     *
     * 以前只删行不删对象，站点每改一次版就在桶里留下一份永远不会被读到的旧图，且没有任何
     * 清理入口。
     *
     * 三条安全边界，缺一不可：
     * 1. **跨书签引用计数**。object key 现在是图片字节的 SHA-256（内容寻址），共用同一张
     *    favicon 的多个站点会指向**同一个 key**，只看本书签就删会删掉别人还在用的图。
     *    内容寻址让这条边界比以前更吃紧 —— 以前只有同一个源 URL 才会撞 key，现在只要字节
     *    相同就会撞，跨站共用的概率大幅上升。
     * 2. **只删裸 key**。`storage_url` 里还有改造前写入的完整 URL，可能指向外部域名，一律不碰。
     * 3. **提交后才删**。事务回滚时行还在、对象却没了才是最糟的结果，所以挂在 afterCommit 上。
     *
     * 即便判断失误代价也有限：key 由字节决定，下一次重抓算出同样的 hash、传回同一个 key。
     */
    private fun scheduleOrphanCleanup(ownerId: String, candidates: Set<String>) {
        if (!scrapperConfig.reclaimOrphanAssets) return
        val keys = candidates.filterNot { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (keys.isEmpty()) return

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 没有事务上下文（理论上不该发生，persist 是 @Transactional 的）时保守跳过，
            // 宁可留下孤儿对象也不要在可能回滚的路径上删东西
            log.warn("[SiteAssetWriter] 无事务上下文，跳过 OSS 孤儿对象回收: ownerId={}, keys={}", ownerId, keys.size)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = deleteUnreferenced(ownerId, keys)
        })
    }

    /** 引用计数归零的 key 才真正删；删除失败只记日志（[OssUtils.delete] 本身已吞异常）。 */
    private fun deleteUnreferenced(ownerId: String, keys: List<String>) {
        runCatching {
            val stillReferenced = siteAssetMapper.selectList(
                KtQueryWrapper(SiteAssetEntity::class.java)
                    .select(SiteAssetEntity::storageUrl)
                    .`in`(SiteAssetEntity::storageUrl, keys)
            ).mapNotNull { it.storageUrl }.toSet()

            val orphans = keys - stillReferenced
            // 账本行要在删对象**之前**取：删完再查也查得到（账本不随桶变），但先取一次能保证
            // 「删了桶里的字节」与「账本知道它没了」用的是同一批 id
            val ledgerRows = ossObjectService.findByKeys(orphans)
            orphans.forEach { OssUtils.delete(it) }
            // 不标的话，账本会一直声称这些对象 ACTIVE，直到次日凌晨的对账从桶那边发现它们没了。
            // 中间这段时间里账本是错的，而账本存在的全部意义就是回答"桶里到底有什么"
            ledgerRows.values.forEach { ossObjectService.markDeleted(it.id) }
            if (orphans.isNotEmpty()) log.info(
                "[SiteAssetWriter] 已回收无引用的 OSS 对象: ownerId={}, deleted={}, keptShared={}",
                ownerId, orphans.size, keys.size - orphans.size
            )
        }.onFailure {
            // 回收失败只是留下孤儿对象，不该反过来影响已经提交的抓取结果
            log.warn("[SiteAssetWriter] OSS 孤儿对象回收失败(忽略): ownerId={}, err={}", ownerId, it.message)
        }
    }

    /** 落一次失败抓取：只留快照，便于事后排查。资产与元数据保持上一次的值不动。 */
    @Transactional(rollbackFor = [Exception::class])
    fun persistFailure(pageId: String, url: String, errorMsg: String?, durationMs: Int) {
        val p = SiteAssetIngestor.projectFailure(pageId, url, errorMsg, durationMs)
        scrapeSnapshotMapper.insert(p.snapshot)
    }

    /** 供管理后台单独改某个字段用（不走抓取流程）。 */
    fun upsertPageMeta(meta: PageMetaEntity) {
        val exists = sitePageMetaMapper.selectById(meta.pageId) != null
        if (exists) sitePageMetaMapper.updateById(meta) else sitePageMetaMapper.insert(meta)
    }

    /** 读取某书签的文字元数据，不存在时给一个未持久化的空实例。 */
    fun pageMetaOf(pageId: String): PageMetaEntity =
        sitePageMetaMapper.selectById(pageId) ?: PageMetaEntity(pageId = pageId)

    /**
     * 批量读取文字元数据，键为 pageId。
     *
     * 与 [pageMetaOf] 的区别不只是条数：**没有行的页面不会出现在结果里**，而不是补一个空实例。
     * 后台列表要区分「抓过、但站点没声明描述」和「从来没抓成功过」，补空实例会把后者伪装成前者。
     * 一条 `in` 查询，避免整页逐行查库。
     */
    fun pageMetaOfBatch(pageIds: Collection<String>): Map<String, PageMetaEntity> {
        if (pageIds.isEmpty()) return emptyMap()
        return sitePageMetaMapper.selectBatchIds(pageIds).associateBy { it.pageId }
    }

    /**
     * 收敛 `scrape_snapshot`：每个书签只留最近 [SNAPSHOT_RETAIN_PER_BOOKMARK] 份。
     *
     * 这是全库唯一一张只写不读、又没有任何清理、还带 GIN 索引存整份 jsonb 响应的表：
     * 每条书签每 30 天的内容刷新都会追加一份完整响应，一年约 12 份 —— 是唯一会线性吃满
     * 磁盘的表。`bookmark_ping_log` / `bookmark_sweep_log` 早有 90 天清理，唯独它没有。
     *
     * 为什么按份数而不按时间过期，见 [ScrapeSnapshotMapper.purgeKeepingLatestPerBookmark]。
     */
    fun purgeOldSnapshots(): Int {
        val deleted = scrapeSnapshotMapper.purgeKeepingLatestPerBookmark(SNAPSHOT_RETAIN_PER_BOOKMARK)
        if (deleted > 0) {
            log.warn("[purgeOldSnapshots] 已清理 $deleted 份历史抓取快照(每书签保留最近 $SNAPSHOT_RETAIN_PER_BOOKMARK 份)")
        }
        return deleted
    }

    companion object {
        /**
         * 每个书签保留的抓取快照份数。
         *
         * 3 份的依据是这张表的用途：回填要的是「最新那份」，多留两份是为了排查「这个字段
         * 是什么时候开始抓不到的」。再多就只是在为一年前的 HTML 付存储费了。
         */
        private const val SNAPSHOT_RETAIN_PER_BOOKMARK = 3

        /** 图标类角色。PAGE 层里只有这两个是「发散判定」的产物，SOCIAL/SCREENSHOT 天然属于页面。 */
        private val ICON_ROLES = listOf(AssetRole.FAVICON, AssetRole.LOGO)
    }
}
