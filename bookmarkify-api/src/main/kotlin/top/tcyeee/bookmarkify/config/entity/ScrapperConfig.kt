package top.tcyeee.bookmarkify.config.entity

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties
import top.tcyeee.bookmarkify.entity.dto.scrape.ImageFormat

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
     * 主解析链路是否顺带抓页面截图。
     *
     * **保持关闭**。截图强制走无头浏览器，而对端的无头并发是有上限的（scrapper 的
     * `HEADLESS_CONCURRENCY`，默认 2，生产容器只有 1GB 内存 / 2 vCPU）。把它并进主链路，
     * 等于让每一条新增书签都去排队等浏览器，"加一个书签要转多久圈"会直接崩掉。
     *
     * 截图走的是另一条路：解析成功后发
     * [BookmarkScreenshotEvent][top.tcyeee.bookmarkify.config.event.BookmarkScreenshotEvent]，
     * 在单线程的截图池上慢慢补（见 `AsyncConfig.BOOKMARK_SCREENSHOT_EXECUTOR`）。用户拿到
     * 书签不受影响，封面晚几秒出现。
     *
     * 这个开关留着是给"想让截图同步出结果"的特殊场景用的，正常部署不要开。
     */
    var screenshot: Boolean = false,

    /**
     * 是否启用截图补抓任务（[BookmarkScreenshotEvent][top.tcyeee.bookmarkify.config.event.BookmarkScreenshotEvent]）。
     *
     * 与 [screenshot] 分开是因为两者控制的不是一件事：那个是"主链路要不要等截图"，
     * 这个是"要不要截图"。关掉它就完全不产生截图负载。
     */
    var screenshotAsync: Boolean = true,

    /**
     * 截图输出格式。默认 WEBP —— 同等观感下体积约为 PNG 的三分之一，而截图是纯展示用途，
     * 有损完全可以接受。OSS 图片处理支持 webp 缩放（见 `OssUtils.IMG_PROCESSABLE_MIME`）。
     */
    var screenshotFormat: ImageFormat = ImageFormat.WEBP,

    /** 有损格式的质量 1–100。封面会被缩到 640px 宽，再高的质量看不出来。 */
    var screenshotQuality: Int = 80,
) {
    /**
     * 启动即校验 [screenshotFormat]，配错就不许起来。
     *
     * [ImageFormat.UNKNOWN] 只存在于**响应**侧（`@JsonEnumDefaultValue`，用来兼容 scrapper
     * 将来新增的格式），请求侧没有这个取值 —— Rust 的 `ImageFormat` 只有 WEBP/PNG/JPEG。
     * 而 `screenshot` 字段是随**每一次** `/scrape` 发出去的，所以配成 UNKNOWN 之后坏掉的
     * 不是截图，是全部抓取：清一色 422，且报错指向"请求体字段对不上"，与截图毫无关联，
     * 排查起来极难联想到这个配置项。启动时炸掉是这里唯一负责任的做法。
     */
    @PostConstruct
    fun validate() {
        require(screenshotFormat != ImageFormat.UNKNOWN) {
            "bookmarkify.scrapper.screenshot-format 只能是 WEBP / PNG / JPEG，不能是 UNKNOWN —— " +
                "该取值仅用于解析 scrapper 响应里的未知格式，发给 scrapper 会让每一次抓取都返回 422"
        }
    }
}
