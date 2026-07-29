package top.tcyeee.bookmarkify.entity
import top.tcyeee.bookmarkify.entity.enums.DisplayMode

import com.baomidou.mybatisplus.core.conditions.Wrapper
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.config.result.PageBean
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.entity.enums.ShareStatus
import top.tcyeee.bookmarkify.utils.BaseUtils
import java.time.LocalDateTime

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

data class BookmarkLivenessConfigUpdateParams(
    @field:Schema(description = "已激活书签检测频率(小时)") val activeCheckIntervalHours: Int,
    @field:Schema(description = "异常书签检测频率(小时)") val abnormalCheckIntervalHours: Int,
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
data class BookmarkPinParams(var linkId: String, var pinned: Boolean)
data class BookmarkIconUpdateParams(
    // 显示设置按展示模式分行：72px 大图上的内边距/背景色与 16px 列表行是两回事
    @field:Schema(description = "展示模式 TILE/LIST") var displayMode: DisplayMode = DisplayMode.TILE,
    @field:Schema(description = "图片内边距") var iconPadding: Int = 25,
    @field:Schema(description = "图标背景色") var iconBgColor: String? = null,
    @field:Schema(description = "人工钉死的资产ID,覆盖自动选择;为空表示走自动") var pinnedAssetId: String? = null,
    @field:Schema(description = "书签简称") var appName: String? = null,
)

/** 管理后台「重新获取」后，应用预览结果：分别决定标题/小图标/大图标(高清 LOGO)是否采用新值 */
data class BookmarkRefetchApplyParams(
    @field:Schema(description = "是否采用新标题") var useNewTitle: Boolean = false,
    @field:Schema(description = "是否采用新小图标") var useNewIcon: Boolean = false,
    @field:Schema(description = "是否采用新大图标(高清 LOGO)") var useNewLogo: Boolean = false,
)

/** 管理后台手动编辑书签基础信息（标题/简介） */
data class BookmarkBasicInfoUpdateParams(
    @field:Schema(description = "书签标题") var title: String? = null,
    @field:Schema(description = "书签简介") var description: String? = null,
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
    var name: String? = null,
    @field:Schema(description = "仅返回重复书签(同一站点被本用户添加了多次)") var duplicatesOnly: Boolean = false,
    @field:Schema(description = "仅返回失效书签(链接存活检测失败)") var invalidOnly: Boolean = false,
) : PageBean() {
    fun toWrapper(restrictBookmarkIds: Set<String>? = null): Wrapper<BookmarkUserLink> {
        val query = KtQueryWrapper(BookmarkUserLink::class.java)
        query.eq(BookmarkUserLink::uid, uid)
            .eq(BookmarkUserLink::deleted, false)
        if (!name.isNullOrBlank()) {
            query.and {
                it.like(BookmarkUserLink::title, name)
                    .or().like(BookmarkUserLink::description, name)
            }
        }
        if (restrictBookmarkIds != null) query.`in`(BookmarkUserLink::bookmarkId, restrictBookmarkIds)
        // 查看"重复书签"时按 bookmarkId 排序，让同一站点的重复项在列表中相邻，便于用户对比和清理
        if (duplicatesOnly) query.orderByAsc(BookmarkUserLink::bookmarkId).orderByAsc(BookmarkUserLink::createTime)
        return query
    }
}


/** 用户状态筛选项：由 deleted / disabled 两个标记组合而成，对外只暴露一个互斥的状态 */
enum class UserStatusFilter { NORMAL, DISABLED, DELETED }

data class UserSearchParams(
    var name: String? = null,
    var status: UserStatusFilter? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<UserInfoEntity> {
        val query = KtQueryWrapper(UserInfoEntity::class.java)
        if (!name.isNullOrBlank()) {
            query.and {
                it.like(UserInfoEntity::nickName, name)
                    .or().like(UserInfoEntity::email, name)
            }
        }
        when (status) {
            UserStatusFilter.NORMAL -> query.eq(UserInfoEntity::deleted, false).eq(UserInfoEntity::disabled, false)
            UserStatusFilter.DISABLED -> query.eq(UserInfoEntity::deleted, false).eq(UserInfoEntity::disabled, true)
            UserStatusFilter.DELETED -> query.eq(UserInfoEntity::deleted, true)
            null -> Unit
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

/** 创建/发布一个书签分享 */
data class ShareCreateParams(
    @field:Schema(description = "要分享的书签(bookmark_user_link.id)列表") val bookmarkUserLinkIds: List<String>,
    @field:Schema(description = "分享文案") val note: String? = null,
    @field:Schema(description = "过期时间(为空表示永不过期)") val expireTime: LocalDateTime? = null,
)

/** 修改自己发布的分享(文案/过期时间) */
data class ShareUpdateParams(
    @field:Schema(description = "分享ID") val id: String,
    @field:Schema(description = "分享文案") val note: String? = null,
    @field:Schema(description = "过期时间(为空表示永不过期)") val expireTime: LocalDateTime? = null,
)

/** 管理后台分享查询入参 */
data class ShareSearchParams(
    var uid: String? = null,
    var status: ShareStatus? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<UserShareEntity> {
        val query = KtQueryWrapper(UserShareEntity::class.java)
        if (!uid.isNullOrBlank()) query.eq(UserShareEntity::uid, uid)
        // EXPIRED 为计算值，不落库，按状态筛选时管理端只区分 正常/已被管理员下架
        if (status != null && status != ShareStatus.EXPIRED) query.eq(UserShareEntity::status, status)
        return query.orderByDesc(UserShareEntity::createTime)
    }
}

/** 创建浏览器插件访问令牌 */
data class AccessTokenCreateParams(
    @field:Schema(description = "用户自定义备注，如「Chrome插件」") val name: String,
)
