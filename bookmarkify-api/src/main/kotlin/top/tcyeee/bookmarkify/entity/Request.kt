package top.tcyeee.bookmarkify.entity

import com.baomidou.mybatisplus.core.conditions.Wrapper
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.config.result.PageBean
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.AiCallScene
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.IconVerdict
import top.tcyeee.bookmarkify.entity.enums.OssObjectSource
import top.tcyeee.bookmarkify.entity.enums.OssObjectState
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import top.tcyeee.bookmarkify.entity.enums.ShareStatus
import top.tcyeee.bookmarkify.entity.enums.SiteLockedField
import top.tcyeee.bookmarkify.entity.enums.UserBehaviorType
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

// 书签巡检配置的入参直接用 BookmarkLivenessConfigValue —— 它与出参、与存库的结构完全一致，
// 单独一份 Params 只是把每个字段再抄一遍。字段默认值的部署理由搬去了那个类的 KDoc。

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

/** 管理后台「重新获取」后，应用预览结果：分别决定标题/小图标/大图标(高清 LOGO)是否采用新值 */
data class BookmarkRefetchApplyParams(
    @field:Schema(description = "是否采用新标题") var useNewTitle: Boolean = false,
    @field:Schema(description = "是否采用新小图标") var useNewIcon: Boolean = false,
    @field:Schema(description = "是否采用新大图标(高清 LOGO)") var useNewLogo: Boolean = false,
)

/**
 * 管理后台手动编辑书签基础信息（标题/简介/简称）。
 *
 * [appName] 原先挂在已删除的 `BookmarkIconUpdateParams` 上（图标设置那个端点），但它跟图标外观
 * 毫无关系 —— 它是 TILE 标题的候选来源（生产 92 个首页里 75 个靠它出标题，`site.short_name`
 * 只有 15 个）。删图标端点时它必须跟着搬到这里，否则后台再也改不了书签简称，且不会报错。
 */
data class BookmarkBasicInfoUpdateParams(
    @field:Schema(description = "书签标题") var title: String? = null,
    @field:Schema(description = "书签简介") var description: String? = null,
    /**
     * 与 [title] / [description] 的 null 语义**不同**：那两个是「没传就不动」，这个是
     * 「传了就写」——包括传空字符串表示清空。清空要能真正落库，因为空简称会被下一轮抓取用
     * manifest.short_name 或 LLM 推断补上，而"没传"与"清空"若不可区分，管理员就没有清空的手段。
     */
    @field:Schema(description = "书签简称;传空串表示清空,不传表示不修改") var appName: String? = null,
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
    /**
     * 只看某一种链接类型的页面。
     *
     * 类型是**站点层**的事实（`site.link_type`），`page` 表里没有这一列 —— 它连 host 都只存了
     * 一份 site.host 的只读冗余副本。所以这里只能按 site_id 做半连接，而不是加一个 eq。
     */
    @field:Schema(description = "链接类型(域名/本地/IP/其他)，按所属站点过滤") var linkType: BookmarkLinkType? = null,
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
        // 判据刻意写成「存在 site 行**且**类型不符才排除」，而不是更直觉的
        // `site_id IN (SELECT id FROM site WHERE link_type = X)`：后者会把 site_id 指不到任何
        // site 行的孤儿页面一并滤掉（空 site_id 确实存在，BookmarkServiceImpl 里另有一处
        // `filter { siteId.isNotBlank() }` 防的就是它）。而后台每一处页面查询都写死了
        // linkType=DOMAIN，于是那批数据本身就坏掉的行在「页面管理」里彻底消失 ——
        // 恰恰是最该被管理员找到的一批。类型未知的不下结论，宁可留在表里。
        // 拼进 SQL 的是枚举常量名，不是外部字符串，没有注入面；site 表是域名量级(远小于 page)。
        linkType?.let {
            query.notExists("SELECT 1 FROM site s WHERE s.id = page.site_id AND s.link_type <> '${it.name}'")
        }
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
    /**
     * 调用时间下界（含）。
     *
     * 加它是为了让巡检轮次能跳过来：一轮巡检触发的重新抓取是**异步**投递的，日志表里既没有
     * 轮次 ID 也没有页面 ID，只有 url 和时间 —— 「这一轮触发的 N 次重抓后来成没成」只能靠
     * 时间窗圈。窗口右界要比轮次结束时刻宽出一截，因为重抓排在解析池里，落库晚于巡检收工。
     */
    @field:Schema(description = "调用时间下界(含)") var createTimeFrom: LocalDateTime? = null,
    @field:Schema(description = "调用时间上界(含)") var createTimeTo: LocalDateTime? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<ScrapperCallLogEntity> {
        val query = KtQueryWrapper(ScrapperCallLogEntity::class.java)
        if (!urlHost.isNullOrBlank()) {
            query.like(ScrapperCallLogEntity::urlHost, urlHost)
        }
        success?.let { query.eq(ScrapperCallLogEntity::success, it) }
        createTimeFrom?.let { query.ge(ScrapperCallLogEntity::createTime, it) }
        createTimeTo?.let { query.le(ScrapperCallLogEntity::createTime, it) }
        return query.orderByDesc(ScrapperCallLogEntity::createTime)
    }
}

/** 管理后台用户行为审计日志查询入参 */
data class UserBehaviorLogSearchParams(
    /** 昵称模糊匹配 或 uid 精确匹配，二选一命中即可 */
    @field:Schema(description = "关键字：昵称快照模糊匹配 或 uid 精确匹配") var keyword: String? = null,
    @field:Schema(description = "行为类型") var behaviorType: UserBehaviorType? = null,
    @field:Schema(description = "发生时间下界(含)") var createTimeFrom: LocalDateTime? = null,
    @field:Schema(description = "发生时间上界(含)") var createTimeTo: LocalDateTime? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<UserBehaviorLogEntity> {
        val query = KtQueryWrapper(UserBehaviorLogEntity::class.java)
        if (!keyword.isNullOrBlank()) {
            query.and {
                it.like(UserBehaviorLogEntity::nickNameSnapshot, keyword)
                    .or().eq(UserBehaviorLogEntity::uid, keyword)
            }
        }
        behaviorType?.let { query.eq(UserBehaviorLogEntity::behaviorType, it) }
        createTimeFrom?.let { query.ge(UserBehaviorLogEntity::createTime, it) }
        createTimeTo?.let { query.le(UserBehaviorLogEntity::createTime, it) }
        return query.orderByDesc(UserBehaviorLogEntity::createTime)
    }
}

/**
 * 失败站点排行查询入参。
 *
 * 刻意**不继承 [PageBean]**：这是一张排行榜，不是一个可以无限往下翻的数据集。做它的目的是
 * 回答「哪几个站点最值得处理」，翻到第 7 页的第 340 名对这个问题没有任何意义，而分页会逼着
 * 聚合语句再跑一遍 count，代价却买不到东西。要看某个域名的全部记录，下钻到调用日志页去看。
 */
data class ScrapperFailedHostParams(
    /**
     * 统计窗口天数。
     *
     * 默认 30 天而不是 7：抓取重复请求由小时级巡检和 30 天的内容重抓周期驱动，7 天的窗口会把
     * 「每 30 天来一次、每次都失败」这一类恰好切掉，而那正是最该被看见的一类浪费。
     */
    @field:Schema(description = "统计窗口天数") var days: Int = 30,
    /** 失败次数门槛。低于它的域名不进榜——一次性抖动人人都有，混进来只会把真正的常客淹掉 */
    @field:Schema(description = "失败次数门槛(含)") var minFailures: Int = 3,
    @field:Schema(description = "排序口径：failedDurationMs / failedCalls / failRate") var sortField: String = "failedDurationMs",
    @field:Schema(description = "返回条数上限") var limit: Int = 50,
) {
    /**
     * 收紧到安全区间。
     *
     * 这些参数会直接进聚合语句，而聚合是全表扫描量级的操作：`days` 给一个 100000 会让它扫完
     * 整张表，`limit` 给一个百万会把整个结果集拉进内存。入参来自后台页面，但接口是公开的。
     */
    fun sanitized() = ScrapperFailedHostParams(
        days = days.coerceIn(1, 365),
        minFailures = minFailures.coerceIn(1, 10_000),
        // 认不出的取值不报错，交给 SQL 的 CASE 落到默认口径 —— 排序方式选错的代价是看错顺序，
        // 不值得让整个页面报一个 400
        sortField = sortField,
        limit = limit.coerceIn(1, 500),
    )
}

/**
 * 管理后台「图标判定总览」下钻列表的入参。
 *
 * 与 [ScrapperFailedHostParams] 同样刻意不分页，理由也一样：这是一张用来**排查规则**的表，
 * 使用方式是"筛出一档、逐行看图、发现共性"，不是往下翻。真要看某个站点的全部候选图，
 * 下钻到书签详情的资产列表去看。
 */
data class IconVerdictQueryParams(
    /** 只看某一档判定结论；null 表示全部 */
    @field:Schema(description = "只看某一档判定结论(IMAGE/MONOGRAM_QUALITY/MONOGRAM_SIZE/NO_ASSET)") var verdict: IconVerdict? = null,
    /**
     * 只看「判成色块但库里有合格候选」的站点。
     *
     * 这是这张表最主要的用法：它筛出来的每一行都是规则本可以做对却没做对的一次，
     * 也就是 §3.1 三个缺陷的实际受害者名单。
     */
    @field:Schema(description = "只看库里有合格候选却被判色块的站点") var onlySalvageable: Boolean = false,
    @field:Schema(description = "返回条数上限") var limit: Int = 300,
) {
    /** 上限收紧到安全区间：接口是公开的，而这个查询会把全部站点级图标行读进内存 */
    fun sanitized() = IconVerdictQueryParams(
        verdict = verdict,
        onlySalvageable = onlySalvageable,
        limit = limit.coerceIn(1, 2000),
    )
}

/** 管理后台系统配置变更记录查询入参 */
data class ConfigChangeLogSearchParams(
    @field:Schema(description = "只看某一组配置(system_config.config_key)") var configKey: String? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<ConfigChangeLogEntity> {
        val query = KtQueryWrapper(ConfigChangeLogEntity::class.java)
        if (!configKey.isNullOrBlank()) query.eq(ConfigChangeLogEntity::configKey, configKey)
        return query.orderByDesc(ConfigChangeLogEntity::createTime)
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

/**
 * 手动触发一轮巡检的入参。
 *
 * 只有一个字段也用 body 而不是 query：这是个有副作用的写操作（会 ping 几百个站点、可能把书签
 * 改判为失联），POST + body 让它不会被浏览器地址栏、爬虫或预取顺手触发一次。
 */
data class SweepTriggerParams(
    @field:Schema(description = "巡检任务(方法名)，只接受仍在运行的两个") var taskLabel: String = "",
)

/** 管理后台书签活性检查日志查询入参 */
data class BookmarkPingLogSearchParams(
    var urlHost: String? = null,
    /**
     * 只看某一个页面的探测历史，书签详情弹窗的「巡检记录」用它。
     *
     * 与 [urlHost] 不能互相替代：一个域名下可以有几十条深链，按 host 筛出来的是**整站**的探测流水，
     * 而详情弹窗要回答的是「**这一页**是从哪一轮开始判死的」——混在一起时首页的 ALIVE 会把
     * 深链自己的 DEAD 淹掉，正好看反。精确匹配，不做模糊。
     */
    @field:Schema(description = "按页面ID精确筛选(page_ping_log.page_id)") var pageId: String? = null,
    /**
     * 只看某一轮巡检探测过的页面，巡检轮次页的下钻抽屉用它。
     *
     * 注意这里查出来的条数等于该轮的 `probed`，**不等于** `candidates`：被站点层短路的页面
     * 本轮压根没探过，按「一次探测一行」的语义不落日志。抽屉里要把这个差额显式说明，
     * 否则会被当成漏数据。
     */
    @field:Schema(description = "按巡检轮次精确筛选(page_ping_log.sweep_id)") var sweepId: String? = null,
    /** 按探测结论筛选。替代了原来的 alive 布尔筛选——那个表达不了「无结论」这一态。 */
    var outcome: PingOutcome? = null,
) : PageBean() {
    fun toWrapper(): Wrapper<PagePingLogEntity> {
        val query = KtQueryWrapper(PagePingLogEntity::class.java)
        if (!urlHost.isNullOrBlank()) {
            query.like(PagePingLogEntity::urlHost, urlHost)
        }
        if (!pageId.isNullOrBlank()) query.eq(PagePingLogEntity::pageId, pageId)
        if (!sweepId.isNullOrBlank()) query.eq(PagePingLogEntity::sweepId, sweepId)
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
