package top.tcyeee.bookmarkify.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/** 网站活性检测入参：任意 URL，不要求已收录为书签 */
data class WebsiteLivenessCheckParams(
    @field:Schema(description = "待检测网站 URL，可不带协议前缀") val url: String,
)

/** 网站活性检测结果：直接透传 scrapper /scrape 返回的全部原始字段，不落库 */
data class WebsiteLivenessCheckVO(
    @field:Schema(description = "本次检测是否成功抓到数据") val success: Boolean,
    @field:Schema(description = "页面标题") val title: String? = null,
    @field:Schema(description = "页面描述") val description: String? = null,
    @field:Schema(description = "OG主图URL") val image: String? = null,
    @field:Schema(description = "网站图标(base64 data URL)") val favicon: String? = null,
    @field:Schema(description = "网站LOGO URL") val logo: String? = null,
    @field:Schema(description = "数据来源：og/twitter_card/json_ld/html/headless") val source: String? = null,
    @field:Schema(description = "是否命中scrapper缓存") val cached: Boolean? = null,
    @field:Schema(description = "截图(仅headless模式，OSS URL或base64)") val screenshot: String? = null,
    @field:Schema(description = "检测失败时的错误信息") val errorMsg: String? = null,
    @field:Schema(description = "抓取成功且该URL命中已有书签时为true，表示已同步覆盖持久化该书签") val synced: Boolean = false,
)
