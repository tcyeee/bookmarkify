package top.tcyeee.bookmarkify.entity.dto

/** 传给 DeepSeek 的候选分类（来自 category 字典） */
data class CategoryCandidate(
    val slug: String,
    val name: String,
    val description: String? = null,
)

/**
 * DeepSeek 提议的分类，**可能是字典里还没有的新词**。
 *
 * 与 [CategoryCandidate] 的区别就是方向：候选是我们喂进去的既有词表，提议是模型吐回来的结果，
 * 其中的新词由调用方决定要不要落进 `category` 字典。
 */
data class ProposedCategory(
    val slug: String,
    val name: String,
)
