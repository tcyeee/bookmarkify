package top.tcyeee.bookmarkify.entity.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max

data class SignInRequest(
    @Schema(description = "账号") var account: String,
    @Max(value = 200) @Schema(description = "密码") var password: String,
)