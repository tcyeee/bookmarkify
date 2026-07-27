package top.tcyeee.bookmarkify.entity.dto

import com.fasterxml.jackson.annotation.JsonProperty

// ────── Request ──────

data class DeepSeekRequest(
    val model: String = "deepseek-chat",
    val messages: List<DeepSeekMessage>,
    val stream: Boolean = false,
    @JsonProperty("max_tokens") val maxTokens: Int = 20,
)

data class DeepSeekMessage(
    val role: String,
    val content: String,
)

// ────── Response ──────

data class DeepSeekResponse(
    val choices: List<DeepSeekChoice>? = null,
)

data class DeepSeekChoice(
    val message: DeepSeekMessage? = null,
)

// ────── AI 内容审核结果 ──────

/**
 * AI 内容审核结果：与 [top.tcyeee.bookmarkify.server.IApiService.inferNsfw] 的保守
 * fail-open（失败即通过）策略不同——发布审核场景下调用失败必须与"内容违规"区分开，
 * 交由调用方按 fail-closed 策略处理（视为未通过并附带兜底理由），而不能悄悄放行。
 */
sealed class AiReviewOutcome {
    data object Pass : AiReviewOutcome()
    data class Rejected(val reason: String) : AiReviewOutcome()
    data object Unavailable : AiReviewOutcome()
}
