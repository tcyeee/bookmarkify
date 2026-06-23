package top.tcyeee.bookmarkify.entity.dto

/** DeepSeek 推荐的相似网站（仅展示，不入库） */
data class SimilarSite(
    val name: String = "",
    val domain: String = "",
    val reason: String = "",
)
