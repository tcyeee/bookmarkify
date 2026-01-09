package top.tcyeee.bookmarkify.entity

import com.baomidou.mybatisplus.core.conditions.Wrapper
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.config.result.PageBean
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum

data class UserPreferenceUpdateParams(
    @field:Schema(description = "主页背景配置ID") var backgroundConfigId: String? = null,
    @field:Schema(description = "书签打开方式") var bookmarkOpenMode: BookmarkOpenMode = BookmarkOpenMode.NEW_TAB,
    @field:Schema(description = "是否开启极简模式") var minimalMode: Boolean = false,
    @field:Schema(description = "书签间距") var bookmarkLayout: BookmarkLayoutMode = BookmarkLayoutMode.DEFAULT,
    @field:Schema(description = "书签图片大小") var bookmarkImageSize: BookmarkImageSize = BookmarkImageSize.MEDIUM,
    @field:Schema(description = "是否显示标题") var showTitle: Boolean = true,
    @field:Schema(description = "翻页方式") var pageMode: PageTurnMode = PageTurnMode.VERTICAL_SCROLL,
)

data class BackSettingParams(
    @field:Schema(description = "背景类型：GRADIENT / IMAGE") val type: BackgroundType,
    @field:Schema(description = "背景ID") val backgroundId: String,
)

data class GradientConfigParams(
    @field:Schema(description = "自定义渐变ID（编辑时必填）") var id: String? = null,
    @field:Schema(description = "渐变色数组，至少2个颜色") var colors: List<String> = emptyList(),
    @field:Schema(description = "渐变方向角度，默认135") var direction: Int = 135,
)

data class AllOfMyBookmarkParams(var name: String? = null) : PageBean()
data class CaptchaSmsParams(val phone: String, val captcha: String)
data class SmsVerifyParams(val phone: String, val smsCode: String)
data class EmailVerifyParams(val email: String, val code: String)
data class SendEmailParams(val email: String)
data class UserDelParams(val password: String)
data class UserInfoUpdateParams(var nickName: String, var phone: String?)
data class BookmarkUpdatePrams(var linkId: String, var title: String, var description: String)
data class AdminLoginParams(val account: String, val password: String)

data class BookmarkSearchParams(var name: String?, var status: ParseStatusEnum?) : PageBean() {
    fun toWrapper(): Wrapper<BookmarkEntity> {
        val query = KtQueryWrapper(BookmarkEntity::class.java)
        if (!name.isNullOrBlank()) {
            query.and {
                it.like(BookmarkEntity::appName, name)
                    .or().like(BookmarkEntity::title, name)
                    .or().like(BookmarkEntity::description, name)
                    .or().like(BookmarkEntity::urlHost, name)
            }
        }
        if (status != null) query.eq(BookmarkEntity::parseStatus, status)
        return query
    }
}

data class UserSearchParams(
    var name: String? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<UserEntity> {
        val query = KtQueryWrapper(UserEntity::class.java)
        if (!name.isNullOrBlank()) {
            query.and {
                it.like(UserEntity::nickName, name)
                    .or().like(UserEntity::email, name)
                    .or().like(UserEntity::phone, name)
            }
        }
        return query
    }
}
