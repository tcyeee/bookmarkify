package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 自部署的 bookmarkify-scrapper 连接配置（接替原 iframely）。
 */
@ConfigurationProperties(prefix = "bookmarkify.scrapper")
data class ScrapperConfig(
    /** scrapper 服务基础地址，如 http://bookmarkify-scraper:3000 */
    var baseUrl: String = "",
    /**
     * 与 scrapper 的 `SCRAPER_AUTH_TOKEN` 对应的共享密钥；调用 /scrape、/ping 时
     * 作为 `Authorization: Bearer <token>` 发送。留空（默认）则不发送该 header——
     * 对应 scrapper 侧未配置 `SCRAPER_AUTH_TOKEN` 时不做鉴权的行为，两边需保持一致。
     */
    var authToken: String = "",

    /**
     * scrapper 写入 OSS 时用的 key 前缀，**必须与 scrapper 侧的 `OSS_KEY_PREFIX` 一致**。
     *
     * API 自己从不用它拼 key（key 由 scrapper 生成并回报），唯一的用途是让对账任务
     * （`FILE-SYSTEM-REFACTOR.md` P2）知道该扫桶里的哪一段前缀。配错的后果是那一整段对象
     * 扫不到、全部被漏记 —— 注意**不会**误判成孤儿（孤儿是"扫到了但没人引用"），
     * 所以配错只是让治理失效，不会导致误删。
     */
    var keyPrefix: String = "scrapper",

    /**
     * 抓到的图片是否落我方 OSS（对应 `assets.download = UPLOAD`）。
     *
     * **默认必须是 true。** 关掉之后 scrapper 只做 PROBE —— 图片正文照样下载（算 contentHash
     * 和真实尺寸必须读字节），但算完即丢，`storageKey` 为空，库里只剩源站直连地址。那种地址
     * 会随源站防盗链/改版随时失效，等于把首页图标的可用性外包给了几百个第三方站点。
     * 留这个开关只为排障时临时对比，不该在任何环境常态关闭。
     */
    var uploadAssets: Boolean = true,

    /**
     * 重抓后是否回收失去最后一个引用者的 OSS 对象（见 `SiteAssetWriter.scheduleOrphanCleanup`）。
     *
     * 关掉只会让桶里攒下读不到的旧图，不影响任何功能 —— 这是个**保险开关**：删对象是不可逆
     * 的线上动作，万一引用计数逻辑出问题，改配置重启即可止血，不必等代码发版。
     */
    var reclaimOrphanAssets: Boolean = true,

    /**
     * 单张图片字节上限，超过则跳过该张并在其 `error` 里记原因。
     *
     * 契约默认 2MB 是给"只探测尺寸"的场景定的；既然现在要真的存下来，社交分享图
     * （og:image 常见 2–6MB）不能被默认值挡在门外，故放宽到 8MB。
     * scrapper 侧另有硬上限 `HARD_MAX_ASSET_BYTES` 兜底，配再大也不会失控。
     */
    var assetMaxBytes: Long = 8L * 1024 * 1024,

    /**
     * 单页最多处理多少张图，超出部分仍出现在 assets[] 里但不下载。
     *
     * 契约默认 20 在图标齐全的站点（manifest 十几个尺寸 + apple-touch 若干 + OG）容易触顶，
     * 恰好把排在后面的大图截掉，故放宽到 40。
     */
    var assetMaxCount: Int = 40,

    /**
     * 是否顺带抓页面截图。
     *
     * 默认关闭且这是**有意的**：开启会强制每条书签都走无头浏览器，抓取耗时从百毫秒级涨到秒级。
     * 截图目前没有任何展示位在用，为它付这个代价不划算。需要时打开即可，落库链路
     * （[SiteAssetIngestor][top.tcyeee.bookmarkify.server.asset.SiteAssetIngestor] 的
     * SCREENSHOT 分支）是通的。
     */
    var screenshot: Boolean = false,
)
