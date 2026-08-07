package top.tcyeee.bookmarkify.entity
import top.tcyeee.bookmarkify.entity.enums.DisplayMode

import com.baomidou.mybatisplus.core.conditions.Wrapper
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.config.result.PageBean
import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.AiCallScene
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.OssObjectSource
import top.tcyeee.bookmarkify.entity.enums.OssObjectState
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import top.tcyeee.bookmarkify.entity.enums.ShareStatus
import top.tcyeee.bookmarkify.entity.enums.SiteLockedField
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

/**
 * 后三项带默认值，与 [BookmarkLivenessConfigValue] 的默认值一致。
 *
 * 不是图省事：API 与后台是两条独立的部署流水线（各自按路径过滤触发），必然存在一段版本错配窗口。
 * API 先上线时，旧的后台包提交的 JSON 里没有这三个字段——没有默认值的话 Jackson 会直接反序列化
 * 失败，那段窗口里巡检配置页**一次都保存不了**。给了默认值，旧后台提交的就是"这三项维持默认"。
 */
data class BookmarkLivenessConfigUpdateParams(
    @field:Schema(description = "已激活书签检测频率(小时)") val activeCheckIntervalHours: Int,
    @field:Schema(description = "异常书签的初次重试间隔(小时)") val abnormalCheckIntervalHours: Int,
    @field:Schema(description = "重试间隔的叠加倍数，1 表示固定间隔不退避") val abnormalBackoffMultiplier: Int = 2,
    @field:Schema(description = "最长重试间隔(小时)") val abnormalMaxIntervalHours: Int = 384,
    @field:Schema(description = "连续多少次探测失败才判定失活") val deadConfirmFailures: Int = 3,
    @field:Schema(description = "内容重新抓取间隔(天)") val contentRefreshIntervalDays: Int,
) {
    fun toValue() = BookmarkLivenessConfigValue(
        activeCheckIntervalHours = activeCheckIntervalHours,
        abnormalCheckIntervalHours = abnormalCheckIntervalHours,
        abnormalBackoffMultiplier = abnormalBackoffMultiplier,
        abnormalMaxIntervalHours = abnormalMaxIntervalHours,
        deadConfirmFailures = deadConfirmFailures,
        contentRefreshIntervalDays = contentRefreshIntervalDays,
    )
}

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
data class BookmarkOpenParams(var linkId: String)
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

data class BookmarkSearchParams(
    var name: String?,
    var status: ParseStatusEnum?,
    /**
     * 只看该站点下的页面（站点→页面的层级下钻）。
     *
     * 刻意不复用 [name] 里那条 `like(urlHost)`：模糊匹配是子串匹配，用 `qq.com` 下钻会把
     * `xxqq.com.cn` 一并捞进来 —— 下钻要的是精确的父子关系，不是搜索。
     */
    @field:Schema(description = "只看该站点下的页面(精确匹配 site_id)") var siteId: String? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<PageEntity> {
        val query = KtQueryWrapper(PageEntity::class.java)
        if (!name.isNullOrBlank()) {
            query.and {
                it.like(PageEntity::appName, name)
                    .or().like(PageEntity::title, name)
                    .or().like(PageEntity::description, name)
                    .or().like(PageEntity::urlHost, name)
            }
        }
        if (status != null) query.eq(PageEntity::parseStatus, status)
        // 排序只加在下钻分支上：站点内按路径排，/a /b /c 相邻才看得出这个站的结构。
        // 全量列表刻意维持原样不排 —— 那是几十万行的全表分页，给它加一个无索引支撑的
        // ORDER BY 是拿一次排序换一个这里根本没人要的顺序。
        if (!siteId.isNullOrBlank()) {
            query.eq(PageEntity::siteId, siteId)
                .orderByAsc(PageEntity::urlPath)
                .orderByAsc(PageEntity::urlQuery)
        }
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
    fun toWrapper(restrictPageIds: Set<String>? = null): Wrapper<BookmarkEntity> {
        val query = KtQueryWrapper(BookmarkEntity::class.java)
        query.eq(BookmarkEntity::uid, uid)
            .eq(BookmarkEntity::deleted, false)
        if (!name.isNullOrBlank()) {
            query.and {
                it.like(BookmarkEntity::title, name)
                    .or().like(BookmarkEntity::description, name)
            }
        }
        if (restrictPageIds != null) query.`in`(BookmarkEntity::pageId, restrictPageIds)
        // 查看"重复书签"时按 pageId 排序，让同一站点的重复项在列表中相邻，便于用户对比和清理
        if (duplicatesOnly) query.orderByAsc(BookmarkEntity::pageId).orderByAsc(BookmarkEntity::createTime)
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

/** 管理后台第三方 AI 调用日志查询入参 */
data class AiCallLogSearchParams(
    @field:Schema(description = "判定对象(域名/标题)模糊搜索") var subject: String? = null,
    @field:Schema(description = "业务场景") var scene: AiCallScene? = null,
    @field:Schema(description = "是否成功") var success: Boolean? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<AiCallLogEntity> {
        val query = KtQueryWrapper(AiCallLogEntity::class.java)
        if (!subject.isNullOrBlank()) {
            query.like(AiCallLogEntity::subject, subject)
        }
        scene?.let { query.eq(AiCallLogEntity::scene, it) }
        success?.let { query.eq(AiCallLogEntity::success, it) }
        return query.orderByDesc(AiCallLogEntity::createTime)
    }
}

/** 管理后台 OSS 对象账本查询入参 */
data class OssObjectSearchParams(
    @field:Schema(description = "object key 模糊搜索") var objectKey: String? = null,
    @field:Schema(description = "写入方") var source: OssObjectSource? = null,
    @field:Schema(description = "对账结论") var state: OssObjectState? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<OssObjectEntity> {
        val query = KtQueryWrapper(OssObjectEntity::class.java)
        if (!objectKey.isNullOrBlank()) {
            query.like(OssObjectEntity::objectKey, objectKey)
        }
        source?.let { query.eq(OssObjectEntity::source, it) }
        state?.let { query.eq(OssObjectEntity::state, it) }
        // 孤儿排前面：这张表存在的意义就是让人先看到"哪些东西没人要"
        return query.orderByDesc(OssObjectEntity::createTime)
    }
}

/**
 * 管理后台巡检轮次查询入参。
 *
 * [onlyBreaker] 是这个接口存在的主要理由：熔断意味着"我方链路坏了、那一轮全表结论不可信"，
 * 是整套巡检里最该被看到的信号，而它此前只有一行会滚掉的 log.error。
 */
data class BookmarkSweepLogSearchParams(
    @field:Schema(description = "按巡检任务筛选") var taskLabel: String? = null,
    @field:Schema(description = "只看被熔断中止的轮次") var onlyBreaker: Boolean = false,
) : PageBean() {
    fun toWrapper(): Wrapper<SweepLogEntity> {
        val query = KtQueryWrapper(SweepLogEntity::class.java)
        if (!taskLabel.isNullOrBlank()) query.eq(SweepLogEntity::taskLabel, taskLabel)
        if (onlyBreaker) query.isNotNull(SweepLogEntity::breakerReason)
        return query.orderByDesc(SweepLogEntity::createTime)
    }
}

/** 管理后台书签活性检查日志查询入参 */
data class BookmarkPingLogSearchParams(
    var urlHost: String? = null,
    /** 按探测结论筛选。替代了原来的 alive 布尔筛选——那个表达不了「无结论」这一态。 */
    var outcome: PingOutcome? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<PagePingLogEntity> {
        val query = KtQueryWrapper(PagePingLogEntity::class.java)
        if (!urlHost.isNullOrBlank()) {
            query.like(PagePingLogEntity::urlHost, urlHost)
        }
        outcome?.let { query.eq(PagePingLogEntity::outcome, it) }
        return query.orderByDesc(PagePingLogEntity::createTime)
    }
}

/**
 * 管理后台站点(域名)列表查询入参。
 *
 * 这里的每一个筛选项都对应后台的一类具体排查：`nsfw`/`verifyFlag` 找需要人工过一遍的站，
 * `alive=false` + `minConsecutiveFail` 找该下线的域名，`brandNameEmpty` 找抓取没拿到品牌名、
 * 磁贴上只能显示裸域名的那批。全部为 null 即「不筛」，不要用哨兵值代替。
 */
data class SiteSearchParams(
    @field:Schema(description = "关键词：域名 / 站点全名 / 站点短名 模糊匹配") var keyword: String? = null,
    @field:Schema(description = "链接类型(域名/本地/IP/其他)") var linkType: BookmarkLinkType? = null,
    @field:Schema(description = "是否 NSFW") var nsfw: Boolean? = null,
    @field:Schema(description = "域名是否可达") var alive: Boolean? = null,
    @field:Schema(description = "是否已人工认证") var verifyFlag: Boolean? = null,
    @field:Schema(description = "仅看品牌名为空的站点") var brandNameEmpty: Boolean? = null,
    @field:Schema(description = "连续探测失败次数下限(含)") var minConsecutiveFail: Int? = null,
    @field:Schema(description = "创建时间起(含)") var createTimeStart: LocalDateTime? = null,
    @field:Schema(description = "创建时间止(含)") var createTimeEnd: LocalDateTime? = null,
    @field:Schema(description = "排序字段：createTime / updateTime / lastCheckAt / consecutiveFail / host") var sortField: String? = null,
    @field:Schema(description = "是否升序") var sortAsc: Boolean = false,
) : PageBean() {
    fun toWrapper(): Wrapper<SiteEntity> {
        val query = KtQueryWrapper(SiteEntity::class.java)
        if (!keyword.isNullOrBlank()) {
            query.and {
                it.like(SiteEntity::host, keyword)
                    .or().like(SiteEntity::brandName, keyword)
                    .or().like(SiteEntity::shortName, keyword)
            }
        }
        linkType?.let { query.eq(SiteEntity::linkType, it) }
        nsfw?.let { query.eq(SiteEntity::nsfw, it) }
        alive?.let { query.eq(SiteEntity::isAlive, it) }
        verifyFlag?.let { query.eq(SiteEntity::verifyFlag, it) }
        // 空品牌名在库里有两种写法（NULL 与空串），只判其一会漏掉另一半
        brandNameEmpty?.let { empty ->
            if (empty) query.and { it.isNull(SiteEntity::brandName).or().eq(SiteEntity::brandName, "") }
            else query.isNotNull(SiteEntity::brandName).ne(SiteEntity::brandName, "")
        }
        minConsecutiveFail?.let { query.ge(SiteEntity::consecutiveFail, it) }
        createTimeStart?.let { query.ge(SiteEntity::createTime, it) }
        createTimeEnd?.let { query.le(SiteEntity::createTime, it) }
        // 排序字段是白名单映射而不是把字符串拼进 SQL：这个值直接来自请求体
        val column = when (sortField) {
            "updateTime" -> SiteEntity::updateTime
            "lastCheckAt" -> SiteEntity::lastCheckAt
            "consecutiveFail" -> SiteEntity::consecutiveFail
            "host" -> SiteEntity::host
            else -> SiteEntity::createTime
        }
        return query.orderBy(true, sortAsc, column)
    }
}

/**
 * 后台手工编辑站点信息。
 *
 * 存在的理由：[SiteSearchParams] 的 `brandNameEmpty` / `verifyFlag` 筛出来的正是「需要人工
 * 过一遍」的那批站点，而在这个类之前，站点侧一个写端点都没有 —— 筛得出来、改不了，
 * 运营动线断在第二步。
 *
 * 每个字段都是 `null = 不改`，不要用空串或 `false` 当哨兵：这个请求体是**部分更新**，
 * 前端只会送用户真正动过的字段。清空某个名字请显式送空串（见 [ISiteService.adminUpdateBasicInfo]）。
 */
data class SiteBasicInfoUpdateParams(
    @field:Schema(description = "站点全名；空串表示清空并交回抓取托管") var brandName: String? = null,
    @field:Schema(description = "站点短名；空串表示清空并交回抓取托管") var shortName: String? = null,
    @field:Schema(description = "人工认证：置 true 后任何抓取都不再覆盖品牌名与图标") var verifyFlag: Boolean? = null,
    @field:Schema(description = "人工改写 NSFW 结论(纠正 AI 误判)") var nsfw: Boolean? = null,
    @field:Schema(description = "NSFW 理由，仅在 nsfw 一并传入时生效") var nsfwReason: String? = null,
    @field:Schema(description = "显式解锁的字段：表示接受抓取值，此后允许被自动覆盖")
    var unlockFields: List<SiteLockedField> = emptyList(),
)

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
