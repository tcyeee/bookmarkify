package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max

/**
 * 书签LOGO
 *
 * 大小SKU: S / M / L
 * 图片类型SKU: PNG / ICO-Base64
 * 外边距: 10% 20% ...
 *
 * Small: 固定使用ico图标转变为Base64, 配置: padding(10%)
 * Middle: 优先使用Img,如果没有则使用ico/base64 配置: padding(10%) Img可能为png并且紧贴外部, 配置padding可以防止这类情况
 * Large:
 *
 * @author tcyeee
 * @date 1/7/26 22:53
 */
@TableName("bookmark_logo")
data class BookmarkLogo(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Max(200) @field:Schema(description = "书签根域名") var urlHost: String,        // sfz.uzuzuz.com.cn
    @field:Schema(description = "小图标base64") var iconBase64: String? = null,
    @field:Schema(description = "最大LOGO尺寸") var maximalImgSize: Int = 0,

    // T
)