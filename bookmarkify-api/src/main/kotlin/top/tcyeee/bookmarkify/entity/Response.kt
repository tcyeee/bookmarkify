package top.tcyeee.bookmarkify.entity

import cn.hutool.core.bean.BeanUtil
import cn.hutool.core.util.IdUtil
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.AiCallScene
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.BookmarkLockedField
import top.tcyeee.bookmarkify.entity.enums.OssAddressing
import top.tcyeee.bookmarkify.entity.enums.OssObjectSource
import top.tcyeee.bookmarkify.entity.enums.OssObjectState
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import top.tcyeee.bookmarkify.entity.enums.ShareStatus
import top.tcyeee.bookmarkify.entity.json.BookmarkDir
import top.tcyeee.bookmarkify.server.asset.BookmarkDisplayPolicy
import top.tcyeee.bookmarkify.server.asset.SiteAssetResolver
import top.tcyeee.bookmarkify.utils.OssUtils
import java.time.LocalDateTime

data class AdminGridConfigVO(
    @field:Schema(description = "表格标识") val gridId: String,
    @field:Schema(description = "列配置(宽度/隐藏/排序)") val storeData: Any? = null,
)

data class BookmarkLivenessConfigVO(
    @field:Schema(description = "已激活书签检测频率(小时)") val activeCheckIntervalHours: Int,
    @field:Schema(description = "异常书签检测频率(小时)") val abnormalCheckIntervalHours: Int,
    @field:Schema(description = "内容重新抓取间隔(天)") val contentRefreshIntervalDays: Int,
) {
    constructor(value: BookmarkLivenessConfigValue) : this(
        activeCheckIntervalHours = value.activeCheckIntervalHours,
        abnormalCheckIntervalHours = value.abnormalCheckIntervalHours,
        contentRefreshIntervalDays = value.contentRefreshIntervalDays,
    )
}

data class BookmarkShow(
    @field:Schema(description = "关联书签ID") var bookmarkId: String? = null,
    @field:Schema(description = "关联用户自定义信息ID") var bookmarkUserLinkId: String? = null,
    @field:Schema(description = "书签标题") var title: String? = null,
    @field:Schema(description = "书签备注") var description: String? = null,
    @field:Schema(description = "完整url") var urlFull: String? = null,
    @field:Schema(description = "基础url") var urlBase: String? = null,
    @field:Schema(description = "是否置顶") var pinned: Boolean = false,
    @field:Schema(description = "书签链接类型(域名/本地/IP/其他)") var linkType: BookmarkLinkType = BookmarkLinkType.OTHER,
    // 图标不再由本类自己拼装：改由 SiteAssetResolver 按展示模式从 site_asset 解析后注入。
    @field:Schema(description = "图标信息(按展示模式解析后的结果)") var logo: BookmarkLogoShowVO = BookmarkLogoShowVO(),
    @field:Schema(description = "网站活性") var isActivity: Boolean? = null,
    @JsonIgnore @field:Schema(description = "用户ID") var uid: String? = null,
    @JsonIgnore @field:Schema(description = "Host(什么都拿不到时的最后兜底)") var urlHost: String? = null,
    @JsonIgnore @field:Schema(description = "疑似涉黄/涉赌等违规内容(NSFW)，供分享审核使用") var nsfw: Boolean = false,
    @field:Schema(description = "用户桌面排布节点ID(书签管理页批量删除/移入文件夹等操作使用此ID，而非 bookmarkUserLinkId)") var layoutNodeId: String? = null,
    @field:Schema(description = "所属文件夹节点ID，无所属文件夹时为 null") var folderId: String? = null,
    @field:Schema(description = "所属文件夹名称，无所属文件夹时为 null") var folderName: String? = null,

    /* ── 以下是 [initDisplay] 计算 [title] 的输入，不直接下发给前端 ──
     * 三层各自的标题分开带上来，由 BookmarkDisplayPolicy 决定用哪个。此前它们被压成一个
     * title 字段，于是"用户改过标题"和"从页面拷来的快照"不可区分，优先级也就无从谈起。 */
    @JsonIgnore @field:Schema(description = "用户自己改的标题；null 表示没改过") var userTitle: String? = null,
    @JsonIgnore @field:Schema(description = "页面标题(bookmark.title)") var pageTitle: String? = null,
    @JsonIgnore @field:Schema(description = "站点短名(site.short_name)") var siteShortName: String? = null,
    @JsonIgnore @field:Schema(description = "站点全名(site.brand_name)") var siteBrandName: String? = null,
    @JsonIgnore @field:Schema(description = "是否站点首页，决定文案优先级") var rootPage: Boolean = true,
) {
    /**
     * 由实体装配。
     *
     * **刻意不再用 `BeanUtil.copyProperties` 覆盖式拷贝。** 原来是先拷 bookmark、再拷 userlink，
     * 靠后者覆盖前者来实现"用户值优先"。这在 `bookmark_user_link.title` 改成「没改过就是 NULL」
     * 之后会直接坏掉：hutool 默认连 null 一起拷，于是绝大多数书签的页面标题会被一个 null 冲掉。
     * 逐字段显式赋值，优先级交给 [BookmarkDisplayPolicy]，不再依赖拷贝顺序这种隐式契约。
     */
    constructor(userlink: BookmarkUserLink, bookmark: BookmarkEntity?, site: SiteEntity?) : this() {
        bookmarkId = userlink.bookmarkId
        bookmarkUserLinkId = userlink.id
        uid = userlink.uid
        layoutNodeId = userlink.layoutNodeId
        urlFull = userlink.urlFull
        pinned = userlink.pinned
        linkType = userlink.linkType

        userTitle = userlink.title
        description = userlink.description ?: bookmark?.description

        pageTitle = bookmark?.title
        urlHost = bookmark?.urlHost ?: site?.host
        isActivity = bookmark?.isActivity
        rootPage = bookmark?.isRootPage ?: true
        urlBase = bookmark?.let { "${it.urlScheme}://${it.urlHost}" }

        siteShortName = site?.shortName
        siteBrandName = site?.brandName
        // 同 mapper SQL 的过渡期处理：两层任一命中都算命中
        nsfw = (site?.nsfw ?: false) || (bookmark?.nsfw ?: false)
    }

    /**
     * 注入按展示模式解析出的图标与最终文案。
     *
     * [resolved] 刻意不给默认值。图片从 bookmark_logo 的扁平列改成 site_asset 一行一图后，
     * 图标改由调用方经 [SiteAssetResolver] 解析注入，而 [logo] 字段自身有默认值——于是漏注入的
     * 调用点照样编译通过，前端只会静默退化成首字母色块，没有任何报错。桌面主视图与添加/导入
     * 完成后的两处 WebSocket 推送都是这么丢的。参数必填，让编译器替我们守住这条边界。
     *
     * [mode] 同理必填：文案优先级与图标优先级在两种模式下都不一样，而且**必须取同一个值** ——
     * 用 TILE 选图、用 LIST 选文案会得到一个自相矛盾的格子。
     */
    fun initDisplay(resolved: SiteAssetResolver.ResolvedLogo?, mode: DisplayMode): BookmarkShow {
        logo = BookmarkLogoShowVO.from(resolved)
        title = BookmarkDisplayPolicy.title(
            userTitle = userTitle,
            pageTitle = pageTitle,
            siteShortName = siteShortName,
            siteBrandName = siteBrandName,
            urlHost = urlHost,
            isRootPage = rootPage,
            mode = mode,
        )
        return this
    }
}

/**
 * 前台书签图标：**按展示模式解析后的单一结果**，不再是"给你几个图位自己挑"。
 *
 * 挑哪张是服务端的策略（见 AssetRolePolicy），前端只负责渲染。这样 TILE 与 LIST
 * 相反的优先级、以及"没有够格的图就走首字母色块"这类判断，都不必在前端复现一遍。
 */
data class BookmarkLogoShowVO(
    @field:Schema(description = "图片地址(已签名并按模式缩放);走首字母色块时为 null") val url: String? = null,
    @field:Schema(description = "这张图实际是什么:FAVICON/LOGO/SOCIAL/SCREENSHOT") val role: AssetRole? = null,
    @field:Schema(description = "可信度:TRUSTED/DEGRADED") val quality: AssetQuality? = null,
    @field:Schema(description = "是否矢量图") val isVector: Boolean = false,
    @field:Schema(description = "为 true 时前端应放弃图片,改用首字母色块") val monogram: Boolean = true,
    @field:Schema(description = "图片内边距") val iconPadding: Int = 25,
    @field:Schema(description = "图标背景色") val iconBgColor: String? = null,
) {
    companion object {
        fun from(r: SiteAssetResolver.ResolvedLogo?): BookmarkLogoShowVO {
            if (r == null) return BookmarkLogoShowVO()
            return BookmarkLogoShowVO(
                url = r.url,
                role = r.role,
                quality = r.quality,
                isVector = r.isVector,
                monogram = r.monogram,
                iconPadding = r.iconPadding,
                iconBgColor = r.iconBgColor,
            )
        }
    }
}

/** 用户端「添加书签」搜索结果：基础信息 + 按 LIST 模式解析出的图标 */
data class BookmarkSearchVO(
    @field:Schema(description = "书签ID") var id: String,
    @field:Schema(description = "书签根域名") var urlHost: String,
    @field:Schema(description = "书签基础HTTP协议") var urlScheme: String,
    @field:Schema(description = "书签简称") var appName: String? = null,
    @field:Schema(description = "书签标题") var title: String? = null,
    @field:Schema(description = "图标信息") var logo: BookmarkLogoShowVO = BookmarkLogoShowVO(),
) {
    constructor(entity: BookmarkEntity, resolved: SiteAssetResolver.ResolvedLogo?) : this(
        id = entity.id,
        urlHost = entity.urlHost,
        urlScheme = entity.urlScheme,
        appName = entity.appName,
        title = entity.title,
        logo = BookmarkLogoShowVO.from(resolved),
    )
}

data class UserInfoShow(
    @field:Schema(description = "UID") var uid: String,
    @field:Schema(description = "用户名称") var nickName: String,
    @field:Schema(description = "用户头像文件") var avatarUrl: String? = null,
    @field:Schema(description = "角色列表") var roles: List<String>? = null,
    @field:Schema(description = "首页路径") var homePath: String? = null,
    @field:Schema(description = "已关联的 Google 邮箱(未关联为 null)") var googleEmail: String? = null,
    @field:Schema(description = "已关联的 GitHub 用户名(未关联为 null)") var githubLogin: String? = null,
) {
    constructor(entity: UserInfoEntity, avatarUrl: String?) : this(
        uid = entity.id, nickName = entity.nickName, avatarUrl = avatarUrl,
        roles = listOf(entity.role.name), googleEmail = entity.googleEmail, githubLogin = entity.githubLogin
    )
}

class BacSettingVO(
    @field:Schema(description = "背景类型") var type: BackgroundType,
    @field:Schema(description = "背景ID") var backgroundLinkId: String,

    /* 如果是图片背景 */
    @field:Schema(description = "图片背景URL") var bacImgFile: UserFileVO? = null,
    /* 如果是渐变色背景 */
    @field:Schema(description = "背景渐变色") var bacColorGradient: Array<String>? = null,
    @field:Schema(description = "背景渐变方向") var bacColorDirection: Int? = null,
)

@Suppress("unused")
class BacGradientVO(
    @field:Schema(description = "背景ID") var id: String? = null,
    @field:Schema(description = "背景渐变色") var colors: Array<String>,
    @field:Schema(description = "背景渐变方向") var direction: Int,
)

/** 默认背景资源合集 */
data class DefaultBackgroundsResponse(
    @field:Schema(description = "默认渐变背景列表") val gradients: List<BacGradientVO> = emptyList(),
    @field:Schema(description = "默认图片背景列表") val images: List<UserFileVO> = emptyList(),
)

data class UserFileVO(
    @field:Schema(description = "文件ID") var id: String,
    @field:Schema(description = "文件完整URL") var fullName: String,
)

data class UserPreferenceVO(
    @field:Schema(description = "书签打开方式") var bookmarkOpenMode: BookmarkOpenMode = BookmarkOpenMode.NEW_TAB,
    @field:Schema(description = "是否开启极简模式") var minimalMode: Boolean = false,
    @field:Schema(description = "书签间距") var bookmarkGap: BookmarkGapMode = BookmarkGapMode.DEFAULT,
    @field:Schema(description = "书签图片大小") var bookmarkImageSize: BookmarkImageSize = BookmarkImageSize.MEDIUM,
    @field:Schema(description = "是否显示标题") var showTitle: Boolean = true,
    @field:Schema(description = "是否显示桌面增加入口") var showDesktopAddEntry: Boolean = true,
    @field:Schema(description = "翻页方式") var pageMode: PageTurnMode = PageTurnMode.VERTICAL_SCROLL,
    @field:Schema(description = "背景配置") var imgBacShow: BacSettingVO? = null,
) {
    constructor(entity: UserPreferenceEntity) : this() {
        BeanUtil.copyProperties(entity, this)
    }
}

data class UserLayoutNodeVO(
    @field:Schema(description = "节点ID") val id: String,
    @field:Schema(description = "父节点ID") val parentId: String? = null,
    @field:Schema(description = "排序") val sort: Int = Int.MAX_VALUE,
    @field:Schema(description = "节点类型") val type: NodeTypeEnum = NodeTypeEnum.BOOKMARK,
    @field:Schema(description = "节点(文件夹)名称") val name: String? = null,

    /* 三选一 */
    @field:Schema(description = "书签信息") var typeApp: BookmarkShow? = null,
    @field:Schema(description = "系统功能入口") var typeFuc: BookmarkFunctionVO? = null,
    @field:Schema(description = "子节点") val children: MutableList<UserLayoutNodeVO> = mutableListOf()
) {

    // 通过书签构造单桌面节点
    constructor(nodeEntity: UserLayoutNodeEntity, bookmarkShow: BookmarkShow) : this(
        id = nodeEntity.id,
        type = NodeTypeEnum.BOOKMARK,
        typeApp = bookmarkShow
    ) {
        BeanUtil.copyProperties(nodeEntity, this)
    }
}


data class BookmarkAdminVO(
    var id: String,
    @field:Schema(description = "书签根域名") var urlHost: String,        // sfz.uzuzuz.com.cn
    @field:Schema(description = "路径URL(不带参数)") var urlPath: String? = null,         // /test/info
    @field:Schema(description = "书签基础HTTP协议") var urlScheme: String, // http or https

    /* 基础信息 */
    @field:Schema(description = "书签简称") var appName: String? = null,
    @field:Schema(description = "书签标题") var title: String? = null,
    @field:Schema(description = "书签备注") var description: String? = null,

    /* 图片资产（site_asset，一行一图）与各展示模式的设置 */
    @field:Schema(description = "该书签的全部图片资产") var assets: List<SiteAssetAdminVO> = emptyList(),
    @field:Schema(description = "各展示模式下的图标设置") var displayPrefs: List<SiteDisplayPrefVO> = emptyList(),

    /* 状态信息 */
    @field:Schema(description = "是否解析成功") var parseStatus: ParseStatusEnum = ParseStatusEnum.PENDING,
    @field:Schema(description = "网站是否活跃") var isActivity: Boolean = false,
    @field:Schema(description = "抓取成功但页面疑似反爬虫/WAF挑战页,内容可能不可靠") var antiCrawlerBlocked: Boolean = false,
    @field:Schema(description = "手动认证状态") var verifyFlag: Boolean = false, // 如果该书签信息都没问题, 添加手动认证状态以后, 即可被搜索到
    @field:Schema(description = "解析失败后的反馈") var parseErrMsg: String? = null,
    @field:Schema(description = "添加时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "最近更新时间") var updateTime: LocalDateTime? = null,  // 最近更新时间创建的时候默认为null,表示是刚创建的
    @field:Schema(description = "命中的分类") var categories: List<CategoryVO> = emptyList(),
    @field:Schema(description = "疑似涉黄/涉赌等违规内容(NSFW)") var nsfw: Boolean = false,

    /* 收录者。书签是全站共享的规范化记录，没有"属主"这一列——归属只存在于 bookmark_user_link。
     * 这里给的是**最早把它加进来的那个用户**，也就是这条记录当初为什么会存在的答案；
     * [ownerCount] 一并下发，否则后台只看到一个头像会误以为全站就这一个人收藏了它。 */
    @field:Schema(description = "最早收录该书签的用户(全部被删则为 null)") var owner: UserAdminVO? = null,
    @field:Schema(description = "收录该书签的用户数") var ownerCount: Int = 0,

    /* 巡检调度状态：后台需要能回答「这条为什么还没被复查」「为什么一直没变」这类问题 */
    @field:Schema(description = "上次成功抓到内容的时间") var lastParseAt: LocalDateTime? = null,
    @field:Schema(description = "上次活性探测时间(不论结论)") var lastCheckAt: LocalDateTime? = null,
    @field:Schema(description = "下次巡检时间") var nextCheckAt: LocalDateTime? = null,
    @field:Schema(description = "连续探测失败次数") var consecutiveFail: Int = 0,
    @field:Schema(description = "被人工锁定、不会被自动抓取覆盖的字段") var lockedFields: List<BookmarkLockedField> = emptyList(),
) {
    constructor(entity: BookmarkEntity) : this(
        id = entity.id,
        urlHost = entity.urlHost,
        urlScheme = entity.urlScheme,
    ) {
        // lockedFields 在实体里是逗号拼接的字符串，对外给数组：让前端判断某个字段是否锁定时
        // 不必自己切字符串。必须把它从自动拷贝里排除——同名但类型不同(String? → List)，
        // 交给 BeanUtil 硬转会抛异常，把整个后台详情接口带下去。
        BeanUtil.copyProperties(entity, this, "lockedFields")
        lockedFields = entity.lockedFieldSet.toList()
    }
}

/**
 * 管理后台的单张资产视图。
 *
 * 后台刻意展示**全部**资产而非仅选中的那张：排查"这个站为什么用了张丑图"时，需要看到
 * 它到底声明了哪些图、各自出处是什么、有没有撞 hash。
 */
data class SiteAssetAdminVO(
    @field:Schema(description = "资产ID") var id: String = "",
    @field:Schema(description = "用途(本服务推导)") var role: AssetRole = AssetRole.FAVICON,
    @field:Schema(description = "出处(scrapper 报告的事实)") var extractor: String = "",
    @field:Schema(description = "可信度") var quality: AssetQuality = AssetQuality.DEGRADED,
    @field:Schema(description = "可直接预览的地址(私有桶已签名)") var url: String? = null,
    @field:Schema(description = "源站原始地址") var resolvedUrl: String = "",
    @field:Schema(description = "真实像素宽") var width: Int? = null,
    @field:Schema(description = "真实像素高") var height: Int? = null,
    @field:Schema(description = "字节数") var byteSize: Long? = null,
    @field:Schema(description = "MIME") var mime: String? = null,
    @field:Schema(description = "是否矢量图") var isVector: Boolean = false,
    @field:Schema(description = "图片字节 sha256") var contentHash: String? = null,
    @field:Schema(description = "同 role 内的自动首选项") var isPrimary: Boolean = false,
    @field:Schema(description = "与本书签其它资产字节相同(说明该站没有独立 LOGO)") var duplicateOfOther: Boolean = false,
    @field:Schema(description = "该张的失败原因") var errorMsg: String? = null,
)

/** 管理后台某展示模式下的图标设置 */
data class SiteDisplayPrefVO(
    @field:Schema(description = "展示模式") var displayMode: DisplayMode = DisplayMode.TILE,
    @field:Schema(description = "图片内边距") var iconPadding: Int = 25,
    @field:Schema(description = "图标背景色") var iconBgColor: String? = null,
    @field:Schema(description = "人工钉死的资产ID") var pinnedAssetId: String? = null,
    @field:Schema(description = "当前该模式下实际会渲染的地址") var previewUrl: String? = null,
    @field:Schema(description = "为 true 表示该模式下会走首字母色块") var monogram: Boolean = true,
)

/** 管理后台「重新获取」的预览结果：重新解析得到的标题与小图标（不落库，仅供前端对比选择） */
data class BookmarkRefetchVO(
    @field:Schema(description = "新解析的网站标题") var title: String? = null,
    @field:Schema(description = "新解析的网站图标地址") var iconUrl: String? = null,
    @field:Schema(description = "新解析的高清LOGO签名地址(私有桶,未抓到为 null)") var logoUrl: String? = null,
)

/** 管理后台「书签检测」结果：直接调用 scrapper 拿到的全部原始字段，附带检测后落库的活性状态 */
data class BookmarkLivenessVO(
    @field:Schema(description = "本次检测是否成功抓到数据") var success: Boolean,
    @field:Schema(description = "新解析的页面标题") var title: String? = null,
    @field:Schema(description = "新解析的页面描述") var description: String? = null,
    @field:Schema(description = "新解析的OG主图URL") var image: String? = null,
    @field:Schema(description = "新解析的网站图标(base64 data URL)") var favicon: String? = null,
    @field:Schema(description = "新解析的网站LOGO URL") var logo: String? = null,
    @field:Schema(description = "数据来源：og/twitter_card/json_ld/html/headless") var source: String? = null,
    @field:Schema(description = "是否命中scrapper缓存") var cached: Boolean? = null,
    @field:Schema(description = "截图(仅headless模式，OSS URL或base64)") var screenshot: String? = null,
    @field:Schema(description = "检测失败时的错误信息") var errorMsg: String? = null,
    @field:Schema(description = "检测后落库的网站活性") var isActivity: Boolean,
    @field:Schema(description = "检测后落库的解析状态") var parseStatus: ParseStatusEnum,
    @field:Schema(description = "抓取成功但页面疑似反爬虫/WAF挑战页,内容可能不可靠") var antiCrawlerBlocked: Boolean = false,
)

/**
 * 管理后台「图片资产 · 重新抓取」的结果。
 *
 * 单独给出 [scrapedAssetCount] 而不是让前端比对前后数量：本次一张图都没抓到时资产会**原样保留**
 * （见 SiteAssetWriter 的"没抓到就不清空"），此时前后数量相同，光看结果分不清是"没变化"还是
 * "抓崩了但保住了旧图"，而这两件事该给管理员的提示完全不同。
 */
data class BookmarkAssetRefetchVO(
    @field:Schema(description = "本次抓取是否成功") var success: Boolean,
    @field:Schema(description = "本次抓取到的图片张数(0 表示保留了原有图片)") var scrapedAssetCount: Int,
    @field:Schema(description = "抓取失败时的错误信息") var errorMsg: String? = null,
    @field:Schema(description = "落库后的最新书签详情") var bookmark: BookmarkAdminVO,
)

/** 管理后台 DeepSeek 生成 appName 建议（不落库，供前端填入编辑框） */
data class AppNameSuggestVO(
    @field:Schema(description = "DeepSeek 推断的书签简称(可能为空)") var appName: String? = null,
)

data class UserAdminVO(
    @field:Schema(description = "用户ID") var id: String,
    @field:Schema(description = "用户昵称") var nickName: String,
    @field:Schema(description = "设备UID") var deviceId: String,
    @field:Schema(description = "邮箱") var email: String? = null,
    // 头像存的是 oss_object 账本ID，私有桶里的裸 key 浏览器直接用不了，必须由服务端签好再下发。
    // 构造函数里刻意不填：签名要查账本，批量列表得走一次 in 查询才不至于 N+1。
    @field:Schema(description = "头像签名地址(私有桶,无头像为 null)") var avatarUrl: String? = null,
    @field:Schema(description = "绑定的 Google 邮箱") var googleEmail: String? = null,
    @field:Schema(description = "绑定的 GitHub 用户名") var githubLogin: String? = null,
    @field:Schema(description = "用户角色") var role: RoleEnum = RoleEnum.USER,
    @field:Schema(description = "是否被删除") var deleted: Boolean = false,
    @field:Schema(description = "是否禁用") var disabled: Boolean = false,
    @field:Schema(description = "是否已验证") var verified: Boolean = false,
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "更新时间") var updateTime: LocalDateTime = LocalDateTime.now(),
) {
    constructor(entity: UserInfoEntity) : this(
        id = entity.id,
        nickName = entity.nickName,
        deviceId = entity.deviceId,
    ) {
        BeanUtil.copyProperties(entity, this)
    }
}

data class BookmarkFunctionVO(
    @field:Schema(description = "功能ID") val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "用户桌面排布ID") val layoutNodeId: String,
    @field:Schema(description = "功能类型") val type: FunctionType,
)

/** 书签命中的分类（精简视图，用于后台列表/详情展示） */
data class CategoryVO(
    var id: String,
    var slug: String,
    var name: String,
    var color: String? = null,
)

data class BookmarkImportItemVO(
    val title: String,
    val url: String,
    val folder: String?,
    val isDuplicate: Boolean,
)

data class BookmarkImportPreviewVO(
    val total: Int,
    val duplicateCount: Int,
    val items: List<BookmarkImportItemVO>,
)

/** 管理后台展示的 scrapper 调用日志条目 */
data class ScrapperCallLogVO(
    @field:Schema(description = "日志ID") var id: String,
    @field:Schema(description = "请求URL") var url: String,
    @field:Schema(description = "URL host") var urlHost: String,
    @field:Schema(description = "是否成功") var success: Boolean,
    @field:Schema(description = "HTTP状态码") var httpStatus: Int? = null,
    @field:Schema(description = "命中来源") var source: String? = null,
    @field:Schema(description = "是否命中scrapper缓存") var cached: Boolean? = null,
    @field:Schema(description = "耗时(ms)") var durationMs: Long = 0,
    @field:Schema(description = "错误信息") var errorMsg: String? = null,
    @field:Schema(description = "调用时间") var createTime: LocalDateTime = LocalDateTime.now(),
) {
    constructor(entity: ScrapperCallLogEntity) : this(
        id = entity.id,
        url = entity.url,
        urlHost = entity.urlHost,
        success = entity.success,
    ) {
        BeanUtil.copyProperties(entity, this)
    }
}

/** 管理后台展示的第三方 AI 调用日志条目 */
data class AiCallLogVO(
    @field:Schema(description = "日志ID") var id: String,
    @field:Schema(description = "服务商") var provider: String,
    @field:Schema(description = "业务场景") var scene: AiCallScene,
    @field:Schema(description = "模型") var model: String? = null,
    @field:Schema(description = "判定对象(域名/标题)") var subject: String? = null,
    @field:Schema(description = "是否成功") var success: Boolean,
    @field:Schema(description = "HTTP状态码") var httpStatus: Int? = null,
    @field:Schema(description = "请求体原文(含完整prompt)") var requestBody: String? = null,
    @field:Schema(description = "响应体原文") var responseBody: String? = null,
    @field:Schema(description = "输入token数") var promptTokens: Int? = null,
    @field:Schema(description = "输出token数") var completionTokens: Int? = null,
    @field:Schema(description = "总token数") var totalTokens: Int? = null,
    @field:Schema(description = "耗时(ms)") var durationMs: Long = 0,
    @field:Schema(description = "错误信息") var errorMsg: String? = null,
    @field:Schema(description = "调用时间") var createTime: LocalDateTime = LocalDateTime.now(),
) {
    constructor(entity: AiCallLogEntity) : this(
        id = entity.id,
        provider = entity.provider,
        scene = entity.scene,
        success = entity.success,
    ) {
        BeanUtil.copyProperties(entity, this)
    }
}

/** 管理后台展示的书签活性检查日志条目 */
data class BookmarkPingLogVO(
    @field:Schema(description = "日志ID") var id: String,
    @field:Schema(description = "书签ID") var bookmarkId: String,
    @field:Schema(description = "URL host") var urlHost: String,
    @field:Schema(description = "探测结论(ALIVE/DEAD/UNKNOWN)") var outcome: PingOutcome,
    @field:Schema(description = "是否存活；结论为 UNKNOWN 时为 null") var alive: Boolean? = null,
    @field:Schema(description = "ping通后是否触发了重新解析") var triggeredParse: Boolean = false,
    @field:Schema(description = "检查时间") var createTime: LocalDateTime = LocalDateTime.now(),
) {
    constructor(entity: BookmarkPingLogEntity) : this(
        id = entity.id,
        bookmarkId = entity.bookmarkId,
        urlHost = entity.urlHost,
        outcome = entity.outcome,
        alive = entity.alive,
    ) {
        BeanUtil.copyProperties(entity, this)
    }
}

/** 分享人信息(用于分享公开查看页) */
data class ShareSharerVO(
    @field:Schema(description = "分享人昵称") var nickName: String,
    @field:Schema(description = "分享人头像签名地址") var avatarUrl: String? = null,
)

/** 发布分享后返回给分享人自己的结果 */
data class UserShareVO(
    @field:Schema(description = "分享ID(即公开链接后缀)") var id: String,
    @field:Schema(description = "分享文案") var note: String? = null,
    @field:Schema(description = "过期时间(为空表示永不过期)") var expireTime: LocalDateTime? = null,
    @field:Schema(description = "分享状态") var status: ShareStatus = ShareStatus.NORMAL,
    @field:Schema(description = "审核驳回理由(status=REVIEW_REJECTED 时有值)") var rejectReason: String? = null,
    @field:Schema(description = "包含的书签数量") var bookmarkCount: Int = 0,
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
) {
    constructor(entity: UserShareEntity, bookmarkCount: Int) : this(
        id = entity.id,
        note = entity.note,
        expireTime = entity.expireTime,
        status = entity.effectiveStatus,
        rejectReason = entity.rejectReason,
        bookmarkCount = bookmarkCount,
        createTime = entity.createTime,
    )
}

/** 分享状态变化推送(如异步 AI 审核未通过被下架) */
data class ShareStatusChangedVO(
    @field:Schema(description = "分享ID") var id: String,
    @field:Schema(description = "变化后的状态") var status: ShareStatus,
    @field:Schema(description = "审核驳回理由") var rejectReason: String? = null,
)

/** 分享公开查看页(无需登录即可访问) */
data class SharePublicVO(
    @field:Schema(description = "分享ID") var id: String,
    @field:Schema(description = "分享文案") var note: String? = null,
    @field:Schema(description = "过期时间(为空表示永不过期)") var expireTime: LocalDateTime? = null,
    @field:Schema(description = "分享状态") var status: ShareStatus = ShareStatus.NORMAL,
    @field:Schema(description = "分享人信息") var sharer: ShareSharerVO,
    @field:Schema(description = "分享的书签列表") var bookmarks: List<BookmarkShow> = emptyList(),
)

/** 管理后台分享列表条目 */
data class UserShareAdminVO(
    @field:Schema(description = "分享ID") var id: String,
    @field:Schema(description = "分享人用户ID") var uid: String,
    @field:Schema(description = "分享人昵称") var nickName: String,
    @field:Schema(description = "分享文案") var note: String? = null,
    @field:Schema(description = "过期时间(为空表示永不过期)") var expireTime: LocalDateTime? = null,
    @field:Schema(description = "分享状态") var status: ShareStatus = ShareStatus.NORMAL,
    @field:Schema(description = "审核驳回理由(status=REVIEW_REJECTED 时有值)") var rejectReason: String? = null,
    @field:Schema(description = "包含的书签数量") var bookmarkCount: Int = 0,
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
) {
    constructor(entity: UserShareEntity, nickName: String, bookmarkCount: Int) : this(
        id = entity.id,
        uid = entity.uid,
        nickName = nickName,
        note = entity.note,
        expireTime = entity.expireTime,
        status = entity.effectiveStatus,
        rejectReason = entity.rejectReason,
        bookmarkCount = bookmarkCount,
        createTime = entity.createTime,
    )
}

/** 访问令牌列表条目(不含明文 token) */
data class AccessTokenVO(
    @field:Schema(description = "令牌ID") var id: String,
    @field:Schema(description = "用户自定义备注") var name: String,
    @field:Schema(description = "展示用前缀") var tokenPrefix: String,
    @field:Schema(description = "最近一次使用时间") var lastUsedAt: LocalDateTime? = null,
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
) {
    constructor(entity: AccessTokenEntity) : this(
        id = entity.id,
        name = entity.name,
        tokenPrefix = entity.tokenPrefix,
        lastUsedAt = entity.lastUsedAt,
        createTime = entity.createTime,
    )
}

/** 创建访问令牌后的一次性返回(仅此一次包含明文 token) */
data class AccessTokenCreatedVO(
    @field:Schema(description = "令牌ID") var id: String,
    @field:Schema(description = "用户自定义备注") var name: String,
    @field:Schema(description = "明文 token，仅此一次返回，请妥善保存") var token: String,
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
)

/** 插件查询网站信息的响应(/extension/site-info) */
data class ExtensionSiteInfoVO(
    @field:Schema(description = "网站标题") var title: String? = null,
    @field:Schema(description = "网站图标，base64 data URL") var favicon: String? = null,
)

/** 管理后台展示的一条 OSS 对象账本记录 */
data class OssObjectVO(
    @field:Schema(description = "文件ID") var id: String,
    @field:Schema(description = "object key") var objectKey: String,
    @field:Schema(description = "字节 sha256") var contentHash: String? = null,
    @field:Schema(description = "key 推导方式") var addressing: OssAddressing,
    @field:Schema(description = "写入方") var source: OssObjectSource,
    @field:Schema(description = "字节数") var size: Long? = null,
    @field:Schema(description = "MIME") var mime: String? = null,
    @field:Schema(description = "像素宽") var width: Int? = null,
    @field:Schema(description = "像素高") var height: Int? = null,
    @field:Schema(description = "对账结论") var state: OssObjectState,
    @field:Schema(description = "最近一次在桶里被确认存在的时间") var lastSeenAt: LocalDateTime? = null,
    @field:Schema(description = "最近一次被引用的时间") var lastRefAt: LocalDateTime? = null,
    @field:Schema(description = "入账时间") var createTime: LocalDateTime = LocalDateTime.now(),
    /** 后台预览用的限时签名地址。私有读桶里裸 key 直接访问必然 403，必须签过才能看 */
    @field:Schema(description = "预览地址(限时签名)") var previewUrl: String? = null,
) {
    constructor(entity: OssObjectEntity) : this(
        id = entity.id,
        objectKey = entity.objectKey,
        addressing = entity.addressing,
        source = entity.source,
        state = entity.state,
    ) {
        BeanUtil.copyProperties(entity, this)
        previewUrl = OssUtils.signAsset(
            entity.objectKey, 128, mime = entity.mime, isVector = entity.isVector
        )
    }
}

/**
 * 一轮 OSS 对账的结果。
 *
 * [orphans] 是这份报告真正的产出：桶里存在、但扫遍所有引用方表都没人指向的对象。
 * 当前阶段只统计不删除。
 */
data class OssReconcileReport(
    @field:Schema(description = "本轮扫描的 key 前缀") var scannedPrefixes: List<String> = emptyList(),
    @field:Schema(description = "桶中对象总数") var bucketObjects: Int = 0,
    @field:Schema(description = "对账前账本行数") var ledgerRowsBefore: Int = 0,
    @field:Schema(description = "桶里有、账本缺，本轮补记的行数") var backfilled: Int = 0,
    @field:Schema(description = "账本有、桶里已不存在，标记为 DELETED 的行数") var markedDeleted: Int = 0,
    @field:Schema(description = "仍被引用的对象数") var referenced: Int = 0,
    @field:Schema(description = "无人引用的孤儿对象数") var orphans: Int = 0,
    @field:Schema(description = "孤儿对象占用字节数(可回收空间)") var orphanBytes: Long = 0,
    @field:Schema(description = "本轮实际回收的对象数(未开启回收时恒为0)") var reclaimed: Int = 0,
    @field:Schema(description = "耗时(ms)") var durationMs: Long = 0,
    @field:Schema(description = "失败原因，成功时为空") var errorMsg: String? = null,
)
