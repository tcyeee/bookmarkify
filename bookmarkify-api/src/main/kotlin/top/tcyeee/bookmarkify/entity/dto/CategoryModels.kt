package top.tcyeee.bookmarkify.entity.dto

/** 传给 DeepSeek 的候选分类（来自 category 字典） */
data class CategoryCandidate(
    val slug: String,
    val name: String,
    val description: String? = null,
)
