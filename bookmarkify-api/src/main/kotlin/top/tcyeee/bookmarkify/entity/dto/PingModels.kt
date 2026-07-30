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

/** `POST /ping` 响应体 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PingResponse(val alive: Boolean = false)
