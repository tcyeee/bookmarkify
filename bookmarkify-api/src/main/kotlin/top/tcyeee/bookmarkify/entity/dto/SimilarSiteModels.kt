package top.tcyeee.bookmarkify.entity.dto

/** DeepSeek 推荐的相似网站（仅展示，不入库）。exists 为后端回填：该域名本地是否已存在。 */
data class SimilarSite(
    val name: String = "",
    val domain: String = "",
    val reason: String = "",
    /** 本地是否已收录（按 urlHost 归一化比对得到，DeepSeek 不返回该字段） */
    var exists: Boolean = false,
)

/** 一键收录入参：待收录的相似网站域名列表（仅含本地尚未存在的） */
data class SimilarIngestParams(
    val domains: List<String> = emptyList(),
)

/** 一键收录进度：通过 WebSocket 逐站推送给发起收录的管理员 */
data class SimilarIngestUpdate(
    /** 原始提交的域名（与前端列表 key 一致，用于精确匹配该站点） */
    val domain: String,
    /** INGESTED=已收录；SKIPPED=抓取失败/幻觉已跳过；EXISTS=本地已存在 */
    val status: String,
)
