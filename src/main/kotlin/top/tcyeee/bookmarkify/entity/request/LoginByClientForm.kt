package top.tcyeee.bookmarkify.entity.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max

/**
 * @author tcyeee
 * @date 2/11/25 23:24
 */
data class LoginByClientForm(
    @Schema(description = "浏览器指纹") @Max(value = 200, message = "浏览器指纹最多50字符") var fingerprint: String,
    @Schema(description = "DeviceID") @Max(value = 50, message = "DeviceID最多50字符") var deviceUid: String,
)