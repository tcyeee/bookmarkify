package top.tcyeee.bookmarkify.entity

import com.baomidou.mybatisplus.core.conditions.Wrapper
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.config.result.PageBean
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.utils.BaseUtils

data class UserPreferenceUpdateParams(
    @field:Schema(description = "主页背景配置ID") var backgroundConfigId: String? = null,
    @field:Schema(description = "书签打开方式") var bookmarkOpenMode: BookmarkOpenMode = BookmarkOpenMode.NEW_TAB,
    @field:Schema(description = "是否开启极简模式") var minimalMode: Boolean = false,
    @field:Schema(description = "书签间距") var bookmarkGap: BookmarkGapMode = BookmarkGapMode.DEFAULT,
    @field:Schema(description = "书签图片大小") var bookmarkImageSize: BookmarkImageSize = BookmarkImageSize.MEDIUM,
    @field:Schema(description = "是否显示标题") var showTitle: Boolean = true,
    @field:Schema(description = "是否显示桌面增加入口") var showDesktopAddEntry: Boolean = true,
    @field:Schema(description = "翻页方式") var pageMode: PageTurnMode = PageTurnMode.VERTICAL_SCROLL,
)

data class AdminGridConfigSaveParams(
    @field:Schema(description = "列配置(vxe-table CustomStoreData)") val storeData: Any? = null,
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

data class EmailVerifyParams(val email: String, val code: String)
data class SendEmailParams(val email: String)
data class GoogleLoginParams(val idToken: String)  // Google Identity Services 返回的 ID Token (JWT)
data class GithubLoginParams(val code: String, val redirectUri: String)  // GitHub OAuth 授权码 + 回调地址(换 token 时需与授权请求一致)
data class UserDelParams(val email: String? = null)
data class UserInfoUpdateParams(var nickName: String)
data class BookmarkUpdatePrams(var linkId: String, var title: String, var description: String)
data class BookmarkIconUpdateParams(
    @field:Schema(description = "图片内边距") var iconPadding: Int = 0,
    @field:Schema(description = "图标背景色") var iconBgColor: String? = null,
    @field:Schema(description = "是否使用高清图") var useHdLogo: Boolean = false,
    @field:Schema(description = "书签简称") var appName: String? = null,
)

/** 管理后台「重新获取」后，应用预览结果：分别决定标题/小图标/大图标(高清 LOGO)是否采用新值 */
data class BookmarkRefetchApplyParams(
    @field:Schema(description = "是否采用新标题") var useNewTitle: Boolean = false,
    @field:Schema(description = "是否采用新小图标") var useNewIcon: Boolean = false,
    @field:Schema(description = "是否采用新大图标(高清 LOGO)") var useNewLogo: Boolean = false,
)
data class AdminLoginParams(val account: String, val password: String)
data class AccountLoginParams(val account: String, val password: String)
data class ChangePasswordParams(val oldPassword: String, val newPassword: String)

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

data class CreateDirParams(
    @field:Schema(description = "要放入文件夹的书签节点ID列表(恰好两个)") val nodeIds: List<String>,
    @field:Schema(description = "文件夹名称") val name: String,
    @field:Schema(description = "文件夹排序值") val sort: Int,
)

data class RenameDirParams(
    @field:Schema(description = "文件夹节点ID") val nodeId: String,
    @field:Schema(description = "新名称") val name: String,
)

data class MoveNodeParams(
    @field:Schema(description = "要移动的书签节点ID") val nodeId: String,
    @field:Schema(description = "目标文件夹节点ID，为 null 时表示移出到根目录") val dirNodeId: String?,
)

data class AllOfMyBookmarkParams(
    var uid: String = BaseUtils.uid(),
    var name: String? = null
) : PageBean() {
    fun toWrapper(): Wrapper<BookmarkUserLink> {
        val query = KtQueryWrapper(BookmarkUserLink::class.java)
        query.eq(BookmarkUserLink::uid, uid)
            .eq(BookmarkUserLink::deleted, false)
        if (!name.isNullOrBlank()) {
            query.and {
                it.like(BookmarkUserLink::title, name)
                    .or().like(BookmarkUserLink::description, name)
            }
        }
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
            }
        }
        return query
    }
}

/** 管理后台新增/修改分类词条的入参（id 为空表示新增） */
data class CategorySaveParams(
    val id: String? = null,
    val slug: String,
    val name: String,
    val description: String? = null,
    val color: String? = null,
    val sort: Int = 0,
)

/** 管理后台手动设置某书签分类的入参 */
data class BookmarkCategoriesParams(
    val categoryIds: List<String> = emptyList(),
)

/** 管理后台 scrapper 调用日志查询入参 */
data class ScrapperCallLogSearchParams(
    var urlHost: String? = null,
    var success: Boolean? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<ScrapperCallLogEntity> {
        val query = KtQueryWrapper(ScrapperCallLogEntity::class.java)
        if (!urlHost.isNullOrBlank()) {
            query.like(ScrapperCallLogEntity::urlHost, urlHost)
        }
        success?.let { query.eq(ScrapperCallLogEntity::success, it) }
        return query.orderByDesc(ScrapperCallLogEntity::createTime)
    }
}

/** 管理后台书签活性检查日志查询入参 */
data class BookmarkPingLogSearchParams(
    var urlHost: String? = null,
    var alive: Boolean? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<BookmarkPingLogEntity> {
        val query = KtQueryWrapper(BookmarkPingLogEntity::class.java)
        if (!urlHost.isNullOrBlank()) {
            query.like(BookmarkPingLogEntity::urlHost, urlHost)
        }
        alive?.let { query.eq(BookmarkPingLogEntity::alive, it) }
        return query.orderByDesc(BookmarkPingLogEntity::createTime)
    }
}
