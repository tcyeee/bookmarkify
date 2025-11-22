package top.tcyeee.bookmarkify.entity.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Size

data class SignUpRequest(
    @Schema(description = "昵称") @Size(min = 5, max = 50, message = "昵称长度控制在5到50字之间") var nickName: String,
    @Schema(description = "邮箱") var mail: String,
    @Schema(description = "密码") var password: @Max(value = 200) String,
)
