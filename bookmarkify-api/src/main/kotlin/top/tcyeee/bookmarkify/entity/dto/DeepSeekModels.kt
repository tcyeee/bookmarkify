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
