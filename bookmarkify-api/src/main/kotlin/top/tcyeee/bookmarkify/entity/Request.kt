package top.tcyeee.bookmarkify.entity

import cn.hutool.core.bean.BeanUtil
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.config.result.PageBean
import top.tcyeee.bookmarkify.entity.entity.BackgroundType
import top.tcyeee.bookmarkify.entity.entity.BookmarkLayoutMode
import top.tcyeee.bookmarkify.entity.entity.BookmarkOpenMode
import top.tcyeee.bookmarkify.entity.entity.PageTurnMode
import top.tcyeee.bookmarkify.entity.entity.UserPreferenceEntity


/**
 * 选择主页背景入参
 */
data class UserPreferenceUpdateParams(
    @field:Schema(description = "主页背景配置ID") var backgroundConfigId: String? = null,
    @field:Schema(description = "书签打开方式") var bookmarkOpenMode: BookmarkOpenMode = BookmarkOpenMode.CURRENT_TAB,
    @field:Schema(description = "是否开启极简模式") var minimalMode: Boolean = false,
    @field:Schema(description = "书签排列方式") var bookmarkLayout: BookmarkLayoutMode = BookmarkLayoutMode.DEFAULT,
    @field:Schema(description = "是否显示标题") var showTitle: Boolean = true,
    @field:Schema(description = "翻页方式") var pageMode: PageTurnMode = PageTurnMode.VERTICAL_SCROLL,
) {
    fun toEntity(uid: String): UserPreferenceEntity =
        UserPreferenceEntity(uid = uid).also { BeanUtil.copyProperties(this, it) }
}

data class BackSettingParams(
    @field:Schema(description = "背景类型：GRADIENT / IMAGE") val type: BackgroundType,
    @field:Schema(description = "背景ID") val backgroundId: String,
)

data class GradientConfigParams(
    @field:Schema(description = "渐变色数组，至少2个颜色") var colors: List<String> = emptyList(),
    @field:Schema(description = "渐变方向角度，默认135") var direction: Int = 135,
)

class AllOfMyBookmarkParams(
    @field:Schema(description = "搜索信息") var name: String? = null,
) : PageBean()

data class CaptchaSmsParams(val phone: String, val captcha: String)
data class SmsVerifyParams(val phone: String, val smsCode: String)
data class EmailVerifyParams(val email: String, val code: String)
data class SendEmailParams(val email: String)
data class HomeItemSortParams(var id: String, var sort: Int)
data class UserDelParams(val password: String)
data class UserInfoUpdateParams(var nickName: String, var phone: String?)
data class BookmarkUpdatePrams(var linkId: String, var title: String, var description: String)
