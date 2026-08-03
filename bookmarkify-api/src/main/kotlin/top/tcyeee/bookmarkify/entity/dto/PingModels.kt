package top.tcyeee.bookmarkify.entity.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * bookmarkify-scrapper `POST /ping` 的契约。
 *
 * `/scrape` 的契约不在这里 —— 它在 [top.tcyeee.bookmarkify.entity.dto.scrape.ScrapeContract]，
 * 与 Rust 侧 `contract.rs` 逐字段对应。本文件此前还留着改造前的一套同名
 * `ScrapeRequest` / `ScrapeResponse`（扁平的 logo/image/favicon 三字段模型），已无人引用且
 * 与新契约同名，`import` 时选错一个就会静默丢掉 assets/screenshot 等全部参数，故一并删除。
 */

/** `POST /ping` 请求体 */
data class PingRequest(val url: String)

/**
 * `POST /ping` 响应体 —— scrapper 报的**事实**，判死策略在
 * [top.tcyeee.bookmarkify.server.liveness.LivenessPolicy.outcomeOf]。
 *
 * 此前这里只有一个 `alive: Boolean`，而那个布尔是 scrapper 按"状态码 < 500"折叠出来的
 * **策略结论**。放在对端有两个后果：改判定规则要改跨服务契约；以及 404/410 这类"页面确实
 * 没了"——用户口中"书签打不开"的绝大多数——被一律报成存活，深链失效永远发现不了。
 *
 * [reachable] 与 [alive] 都可空，是为了让两个服务能各自独立发布：部署工作流按目录分别触发，
 * 必然存在版本错配的窗口。新字段读不到就回退到旧字段，见 `ApiServiceImpl.pingWebsite`。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PingResponse(
    /** 是否拿到了目标站点的 HTTP 响应；null 表示对端是尚未升级的旧版 scrapper */
    val reachable: Boolean? = null,
    /** 最终一跳的 HTTP 状态码；[reachable] 为 false 时为 null */
    val status: Int? = null,
    /** 被 scrapper 的 SSRF 策略拒绝。**不是**关于站点的事实 */
    val blocked: Boolean = false,
    /** 实际用到的方法：`HEAD`，或对 HEAD 不支持的服务器回退成的 `GET` */
    val method: String? = null,
    /** 跟随了几跳重定向 */
    val redirects: Int = 0,
    /** 探测耗时 */
    val elapsedMs: Long? = null,
    /** **已废弃**：旧版 scrapper 唯一的字段，只在 [reachable] 为 null 时才读它 */
    val alive: Boolean? = null,
)
