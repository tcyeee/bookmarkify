package top.tcyeee.bookmarkify.entity.dto.scrape

import org.slf4j.LoggerFactory
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.server.asset.AssetRolePolicy
import top.tcyeee.bookmarkify.utils.OssUtils
import java.net.URI
import java.time.LocalDateTime

/**
 * 从 scrapper 的**事实**派生出便于业务代码消费的扁平视图。
 *
 * 这些派生刻意放在 API 侧而非 scrapper 侧：「哪张图算 LOGO」是书签鸭的策略，不是网页的
 * 事实。scrapper 只报 `extractor`，映射规则集中在 [AssetRolePolicy]。
 *
 * 供只需要"给我一张 logo 就行"的旧调用点使用；需要完整资产列表的场景请直接读
 * `response.assets` 或走 `site_asset` 表。
 */

/** 文件级 logger：这里全是扩展属性，没有类可以挂 `T.log` 扩展 */
private val log = LoggerFactory.getLogger("top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeResponseExt")

/** 页面标题 */
val ScrapeResponse.title: String? get() = meta?.title

/** 页面描述 */
val ScrapeResponse.description: String? get() = meta?.description

/** 站点短名（manifest.short_name），大图模式下方那行短文案 */
val ScrapeResponse.shortName: String? get() = meta?.shortName

/**
 * 标题的出处，作为整次抓取的"代表来源"。
 *
 * 契约里出处是**逐字段**的（title 可能来自 OG 而 description 来自 meta[name]），
 * 这里取标题的出处仅供日志与列表展示；完整信息在 `scrape_snapshot` 里。
 */
val ScrapeResponse.primarySource: String? get() = meta?.sources?.get("title")?.extractor?.name

/** 是否命中 scrapper 侧缓存 */
val ScrapeResponse.cached: Boolean get() = fetch.fromCache

/**
 * 按用途挑一张最合适的资产地址（优先落了对象存储的那张）。
 *
 * 落存储的资产给出的是 **object key**，桶是私有读的，必须经 [OssUtils.signAsset] 换成限时
 * 签名地址才能直接给浏览器；没落存储的只能回退到源站直连地址，那种地址本就公开，不签。
 */
private fun ScrapeResponse.bestUrlOf(role: AssetRole): String? = assets
    .filter { it.error == null && (it.storageKey != null || it.resolvedUrl.isNotBlank()) }
    .filter { AssetRolePolicy.classify(it.extractor).first == role }
    .maxByOrNull { a ->
        val trusted = if (AssetRolePolicy.classify(a.extractor).second.name == "TRUSTED") 1_000_000 else 0
        trusted + minOf(a.width ?: 0, a.height ?: 0)
    }
    ?.let { asset ->
        asset.storageKey?.let { OssUtils.signAsset(it) } ?: asset.resolvedUrl.also {
            // 走到这里说明本次抓取没请求 UPLOAD（或那张图上传失败）。以前这是完全静默的，
            // 于是"图片一律进 OSS"整条链路空转了很久都没人发现
            log.warn(
                "[bestUrlOf] {} 未落 OSS，回退源站直连: extractor={}, url={}, finalUrl={}",
                role, asset.extractor, it, fetch.finalUrl
            )
        }
    }

/** 最合适的网站图标地址 */
val ScrapeResponse.faviconUrl: String? get() = bestUrlOf(AssetRole.FAVICON)

/** 最合适的品牌 LOGO 地址 */
val ScrapeResponse.logoUrl: String? get() = bestUrlOf(AssetRole.LOGO)

/** 社交分享图地址（即旧代码里的"OG 图"）*/
val ScrapeResponse.socialUrl: String? get() = bestUrlOf(AssetRole.SOCIAL)

/** 截图地址（仅 headless 模式且已落存储时存在）；同样需要签名，未落存储时回退内联 data URL */
val ScrapeResponse.screenshotUrl: String?
    get() = screenshot?.storageKey?.let { OssUtils.signAsset(it) } ?: screenshot?.dataUrl

/**
 * 把抓取结果填充进书签实体（取代旧 `ScrapeResponse.entity(bookmark)`）。
 *
 * 只写 `bookmark` 主表上仍然属于它的字段；标题/描述同时也会由
 * [SiteAssetWriter][top.tcyeee.bookmarkify.server.asset.SiteAssetWriter] 落进
 * `site_page_meta`，主表这份是给列表查询用的冗余。
 */
fun ScrapeResponse.applyTo(bookmark: PageEntity): PageEntity = bookmark.apply {
    title = this@applyTo.title
    description = this@applyTo.description
    isActivity = true
    parseStatus = ParseStatusEnum.SUCCESS
    parseErrMsg = null
    antiCrawlerBlocked = diagnostics.antiCrawler?.detected == true
    updateTime = LocalDateTime.now()
    this@applyTo.upgradedSchemeOf(this)?.let {
        log.info(
            "[applyTo] 抓取落地在 https，升级页面协议: pageId=$id, urlHost=$urlHost, {}->{}, finalUrl={}",
            urlScheme, it, this@applyTo.fetch.finalUrl,
        )
        urlScheme = it
    }
}

/**
 * 抓取跟随重定向后真正落地的协议，仅在它比库里记着的更安全时给出。
 *
 * `page.urlScheme` 来自用户当初提交的那个网址（`WebsiteParser.urlWrapper` 解析 raw URL），
 * 此前**从不回写**。于是一条当年用 `http://` 收藏、站点后来全站上了 https 的书签，会永远
 * 以 http 为抓取目标：`page.rawUrl` 是拿这一列拼出来的，每一次抓取和活性探测都要先白吃一跳
 * 301，后台的「协议」列和 `site.rootUrl` 也跟着一直是错的。而终点其实一直在
 * `fetch.finalUrl` 里躺着，只是没人读。
 *
 * 判据刻意收得很紧：
 * - **只升不降。** https 的记录被某一次落在 http 的抓取改写，等于把抓取目标往回退一档；
 *   真正只有 http 的站点本来就记着 http，无事可做。
 * - **authority 必须完全一致。** `example.com` 跳到 `www.example.com` 说明这条记录指向的
 *   根本是另一个 host，那是 canonical 重定向要解决的问题，改一个 scheme 字段只会把
 *   「记错了地址」伪装成「记对了」。scheme 不参与 page 的去重四元组
 *   （见 [PageEntity]），所以单独改它不会造成重复页面；改 host 则会。
 */
private fun ScrapeResponse.upgradedSchemeOf(page: PageEntity): String? {
    if (!page.urlScheme.equals("http", ignoreCase = true)) return null
    val final = runCatching { URI(fetch.finalUrl) }.getOrNull() ?: return null
    if (!"https".equals(final.scheme, ignoreCase = true)) return null
    return if (final.authority == page.urlHost) "https" else null
}
