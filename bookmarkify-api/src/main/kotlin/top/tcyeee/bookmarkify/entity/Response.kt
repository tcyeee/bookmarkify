package top.tcyeee.bookmarkify.entity

import cn.hutool.core.bean.BeanUtil
import cn.hutool.core.util.IdUtil
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.AiCallScene
import top.tcyeee.bookmarkify.entity.enums.AssetOwnerType
import top.tcyeee.bookmarkify.entity.enums.AssetQuality
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.entity.enums.AssetRole
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.PageLockedField
import top.tcyeee.bookmarkify.entity.enums.OssAddressing
import top.tcyeee.bookmarkify.entity.enums.OssObjectSource
import top.tcyeee.bookmarkify.entity.enums.OssObjectState
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import top.tcyeee.bookmarkify.entity.enums.ShareStatus
import top.tcyeee.bookmarkify.entity.enums.SiteLockedField
import top.tcyeee.bookmarkify.entity.json.BookmarkDir
import top.tcyeee.bookmarkify.server.asset.BookmarkDisplayPolicy
import top.tcyeee.bookmarkify.server.asset.SiteAssetResolver
import top.tcyeee.bookmarkify.utils.OssUtils
import top.tcyeee.bookmarkify.utils.WebsiteParser
import java.time.LocalDateTime

data class AdminGridConfigVO(
    @field:Schema(description = "表格标识") val gridId: String,
    @field:Schema(description = "列配置(宽度/隐藏/排序)") val storeData: Any? = null,
)

// 书签巡检配置的出参直接用 BookmarkLivenessConfigValue —— 纯 admin 端点，没有要藏的字段，
// 单独一份 VO 只是把每个字段再抄一遍，外加一份漏一行也不报错的手写映射。

data class BookmarkShow(
    @field:Schema(description = "关联书签ID") var pageId: String? = null,
    @field:Schema(description = "关联用户自定义信息ID") var bookmarkId: String? = null,
    @field:Schema(description = "书签标题") var title: String? = null,
    /**
     * 磁贴形态该显示的文案（首页书签取站点短名），由 [initDisplay] 一并算出。
     *
     * 之所以与 [title] 并存而不是让调用方换个 mode 重新解析一遍：**同一条书签在首页上会被渲染
     * 两次** —— 置顶区是大图短文案的磁贴，下方的文件夹卡片里还有一行列表行。一份 VO 供两处使用，
     * 只带一个文案就必然有一处是错的。带两个文案的代价是每条书签多一个短字符串，而两处各解析
     * 一次的代价是整棵桌面树的资产解析翻倍（[SiteAssetResolver.resolveBatch] 是这条链路上唯一
     * 的数据库开销）。
     *
     * 图标仍是按调用方那一个 mode 解析的（置顶区拿到的是 LIST 那张）：文案是纯函数、白拿，
     * 而图标要再查一遍资产表并重新签名。56px 的磁贴用 LIST 的 64px 源图看不出差别。
     */
    @field:Schema(description = "磁贴形态的标题(置顶区用;首页书签为站点短名)") var tileTitle: String? = null,
    @field:Schema(description = "书签备注") var description: String? = null,
    @field:Schema(description = "完整url") var urlFull: String? = null,
    @field:Schema(description = "基础url") var urlBase: String? = null,
    @field:Schema(description = "是否置顶") var pinned: Boolean = false,
    @field:Schema(description = "置顶区排序(越小越靠前;非置顶书签无意义)") var pinnedSort: Int = 0,
    @field:Schema(description = "书签链接类型(域名/本地/IP/其他)") var linkType: BookmarkLinkType = BookmarkLinkType.OTHER,
    // 图标不再由本类自己拼装：改由 SiteAssetResolver 按展示模式从 site_asset 解析后注入。
    @field:Schema(description = "图标信息(按展示模式解析后的结果)") var logo: BookmarkLogoShowVO = BookmarkLogoShowVO(),
    @field:Schema(description = "网站活性") var isActivity: Boolean? = null,
    @JsonIgnore @field:Schema(description = "用户ID") var uid: String? = null,
    @JsonIgnore @field:Schema(description = "Host(什么都拿不到时的最后兜底)") var urlHost: String? = null,
    @JsonIgnore @field:Schema(description = "疑似涉黄/涉赌等违规内容(NSFW)，供分享审核使用") var nsfw: Boolean = false,
    @field:Schema(description = "用户桌面排布节点ID(书签管理页批量删除/移入文件夹等操作使用此ID，而非 bookmarkId)") var layoutNodeId: String? = null,
    @field:Schema(description = "所属文件夹节点ID，无所属文件夹时为 null") var folderId: String? = null,
    @field:Schema(description = "所属文件夹名称，无所属文件夹时为 null") var folderName: String? = null,

    /* ── 以下是 [initDisplay] 计算 [title] 的输入，不直接下发给前端 ──
     * 三层各自的标题分开带上来，由 BookmarkDisplayPolicy 决定用哪个。此前它们被压成一个
     * title 字段，于是"用户改过标题"和"从页面拷来的快照"不可区分，优先级也就无从谈起。 */
    @JsonIgnore @field:Schema(description = "用户自己改的标题；null 表示没改过") var userTitle: String? = null,
    @JsonIgnore @field:Schema(description = "页面标题(bookmark.title)") var pageTitle: String? = null,
    @JsonIgnore @field:Schema(description = "站点短名(site.short_name)") var siteShortName: String? = null,
    @JsonIgnore @field:Schema(description = "页面简称(page.app_name)：manifest 短名，缺失时由 DeepSeek 推断") var pageAppName: String? = null,
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
    constructor(userlink: BookmarkEntity, bookmark: PageEntity?, site: SiteEntity?) : this() {
        pageId = userlink.pageId
        bookmarkId = userlink.id
        uid = userlink.uid
        layoutNodeId = userlink.layoutNodeId
        urlFull = userlink.urlFull
        pinned = userlink.pinned
        pinnedSort = userlink.pinnedSort
        linkType = userlink.linkType

        userTitle = userlink.title
        description = userlink.description ?: bookmark?.description

        pageTitle = bookmark?.title
        pageAppName = bookmark?.appName
        urlHost = bookmark?.urlHost ?: site?.host
        isActivity = bookmark?.isActivity
        rootPage = bookmark?.isRootPage ?: true
        urlBase = bookmark?.let { "${it.urlScheme}://${it.urlHost}" }

        siteShortName = site?.shortName
        siteBrandName = site?.brandName
        // NSFW 是站点级判定，页面级那份副本已删（见 SiteEntity.nsfw）
        nsfw = site?.nsfw ?: false
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
     * 用 TILE 选图、用 LIST 选文案会得到一个自相矛盾的格子。[tileTitle] 是这条规则唯一的例外，
     * 理由见它自己的注释：那一份是给**另一处**渲染（置顶区磁贴）用的，不参与本格子的显示。
     */
    fun initDisplay(resolved: SiteAssetResolver.ResolvedLogo?, mode: DisplayMode): BookmarkShow {
        logo = BookmarkLogoShowVO.from(resolved)
        title = titleFor(mode)
        tileTitle = if (mode == DisplayMode.TILE) title else titleFor(DisplayMode.TILE)
        return this
    }

    private fun titleFor(mode: DisplayMode): String? = BookmarkDisplayPolicy.title(
        userTitle = userTitle,
        pageTitle = pageTitle,
        siteShortName = siteShortName,
        pageAppName = pageAppName,
        siteBrandName = siteBrandName,
        urlHost = urlHost,
        isRootPage = rootPage,
        mode = mode,
    )
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
    // 搜索现在只在 site 层匹配（见 BookmarkServiceImpl.search），appName 权威值随之改读
    // site.shortName（而不是过渡期字段 PageEntity.appName）——两者本应一致，但站点信息
    // 是首页抓取权威写入的，比某条深链残留的旧值更可信。
    constructor(entity: PageEntity, site: SiteEntity, resolved: SiteAssetResolver.ResolvedLogo?) : this(
        id = entity.id,
        urlHost = entity.urlHost,
        urlScheme = entity.urlScheme,
        appName = site.shortName,
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
    /**
     * 所属站点ID。
     *
     * 页面层的一半信息其实挂在站点上（品牌名/图标/NSFW/域名活性），后台看一行页面时
     * 「它属于哪个站」是唯一能把两张表接起来的钥匙 —— 不下发就只能拿 urlHost 去猜，
     * 而 host 是可以重复指向同一站点的冗余副本，不是主键。
     */
    @field:Schema(description = "所属站点ID") var siteId: String = "",
    @field:Schema(description = "书签根域名") var urlHost: String,        // sfz.uzuzuz.com.cn
    @field:Schema(description = "路径URL(不带参数)") var urlPath: String? = null,         // /test/info
    /* query 与 fragment 是 canonical 四元组的一部分，不是可省的细节：少了它们，
     * `/watch?v=A` 与 `/watch?v=B` 在后台是**同一行**，而拆开这两者正是 DeepLinkSplitRepair
     * 干的事。站点下钻列表整列都是同域同路径的深链，没有这两个字段就完全无法区分。 */
    @field:Schema(description = "规范化后的查询参数，无参数为空串") var urlQuery: String = "",
    @field:Schema(description = "路由型 fragment(#/… / #!…)，页内锚点不存") var urlFragment: String = "",
    @field:Schema(description = "书签基础HTTP协议") var urlScheme: String, // http or https
    /**
     * 链接类型。由 `urlHost` 现算，**不在 BeanUtil 拷贝范围内**（`page` 表没有这一列）。
     *
     * 后台靠它把「这条书签根本不会被抓取」这件事说明白：非 DOMAIN 的书签（localhost、
     * 裸 IP）从来没有、也永远不会有标题/图标/元数据，详情页上那些空字段不是抓取失败，
     * 是我方主动不抓（见 [top.tcyeee.bookmarkify.utils.ScrapeTargetGuard]）。不下发这一列，
     * 后台就只能拿这些书签当"抓取失败"处理，管理员会一遍遍去点重抓。
     */
    @field:Schema(description = "链接类型(域名/本地/IP/其他)，非域名不参与抓取") var linkType: BookmarkLinkType = BookmarkLinkType.OTHER,

    /* 基础信息 */
    @field:Schema(description = "书签简称") var appName: String? = null,
    @field:Schema(description = "书签标题") var title: String? = null,
    @field:Schema(description = "书签备注") var description: String? = null,

    /* 图片资产（site_asset，一行一图）与各展示模式下的取图结果 */
    @field:Schema(description = "该书签的全部图片资产") var assets: List<SiteAssetAdminVO> = emptyList(),
    @field:Schema(description = "各展示模式下规则实际选出的渲染结果") var iconRenders: List<IconRenderVO> = emptyList(),

    /* 状态信息 */
    @field:Schema(description = "是否解析成功") var parseStatus: ParseStatusEnum = ParseStatusEnum.PENDING,
    @field:Schema(description = "网站是否活跃") var isActivity: Boolean = false,
    @field:Schema(description = "抓取成功但页面疑似反爬虫/WAF挑战页,内容可能不可靠") var antiCrawlerBlocked: Boolean = false,
    @field:Schema(description = "手动认证状态") var verifyFlag: Boolean = false, // 如果该书签信息都没问题, 添加手动认证状态以后, 即可被搜索到
    @field:Schema(description = "解析失败后的反馈") var parseErrMsg: String? = null,
    @field:Schema(description = "添加时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "最近更新时间") var updateTime: LocalDateTime? = null,  // 最近更新时间创建的时候默认为null,表示是刚创建的
    @field:Schema(description = "命中的分类") var categories: List<CategoryVO> = emptyList(),
    /** 来自 `site.nsfw`（站点级判定）。**不在 BeanUtil 拷贝范围内**，由调用方按 siteId 显式回填。 */
    @field:Schema(description = "疑似涉黄/涉赌等违规内容(NSFW，站点级判定)") var nsfw: Boolean = false,

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
    @field:Schema(description = "被人工锁定、不会被自动抓取覆盖的字段") var lockedFields: List<PageLockedField> = emptyList(),

    /**
     * 最近一次抓取留下的页面元数据（`page_meta` 一行）。
     *
     * 与上面的 `title` / `description` 不是重复：主表那两列是**当前生效值**，可能被管理员
     * 手工改过并加了锁；这里是**抓取原样**，加上主表根本没有的抓取事实（走的哪一层、
     * HTTP 状态码、canonical、语言、主题色、字段级出处）。「标题为什么是这个」「这页到底
     * 抓没抓通」只有对着这份才答得出来。抓取从未成功过的页面为 null。
     */
    @field:Schema(description = "最近一次抓取的页面元数据(page_meta)") var pageMeta: PageMetaAdminVO? = null,
) {
    constructor(entity: PageEntity) : this(
        id = entity.id,
        urlHost = entity.urlHost,
        urlScheme = entity.urlScheme,
    ) {
        // lockedFields 在实体里是逗号拼接的字符串，对外给数组：让前端判断某个字段是否锁定时
        // 不必自己切字符串。必须把它从自动拷贝里排除——同名但类型不同(String? → List)，
        // 交给 BeanUtil 硬转会抛异常，把整个后台详情接口带下去。
        BeanUtil.copyProperties(entity, this, "lockedFields")
        lockedFields = entity.lockedFieldSet.toList()
        // page 表没有 link_type 这一列，BeanUtil 拷不到，必须显式算。
        // 漏了这一句不会报错，字段只会永远停在 OTHER —— 正是 CLAUDE.md 里记着的那个坑
        linkType = WebsiteParser.classifyLinkType(entity.urlHost)
    }
}

/**
 * 管理后台的页面元数据视图（`page_meta` 一行一页）。
 *
 * 这张表此前完全没有对外出口：抓取往里写，谁也不读。后果是后台能看到"标题是什么"，
 * 却看不到"这个标题是从 og:title 来的还是从 <title> 兜底来的"、"这一页是 HTTP 直取还是
 * 退到了无头浏览器"、"抓的时候服务端回的是 200 还是 403" —— 而排查抓取质量问题需要的
 * 恰好是后面这些。
 */
data class PageMetaAdminVO(
    @field:Schema(description = "抓取到的页面标题(未经人工覆盖)") var title: String? = null,
    @field:Schema(description = "抓取到的页面描述(未经人工覆盖)") var description: String? = null,
    @field:Schema(description = "本页声明的站点名(og:site_name)") var siteName: String? = null,
    @field:Schema(description = "本页声明的站点短名(manifest.short_name)") var siteShortName: String? = null,
    @field:Schema(description = "页面自己声明的 canonical 地址") var canonicalUrl: String? = null,
    @field:Schema(description = "页面语言(html lang)") var lang: String? = null,
    @field:Schema(description = "主题色(meta theme-color)") var themeColor: String? = null,
    /** 原样下发的 JSON 字符串：形如 `{"title":{"extractor":"OG","rawKey":"og:title"}}`。 */
    @field:Schema(description = "各字段出处(JSON 原文)") var metaSources: String? = null,
    @field:Schema(description = "实际抓取层 HTTP/HEADLESS") var fetchLayer: String? = null,
    @field:Schema(description = "抓取时目标站返回的 HTTP 状态码") var httpStatus: Int? = null,
    @field:Schema(description = "疑似反爬挑战页,内容不可靠") var antiCrawler: Boolean = false,
    @field:Schema(description = "本次抓取时间") var fetchedAt: LocalDateTime? = null,
    @field:Schema(description = "该行更新时间") var updateTime: LocalDateTime? = null,
) {
    constructor(entity: PageMetaEntity) : this(
        title = entity.title,
        description = entity.description,
        siteName = entity.siteName,
        siteShortName = entity.siteShortName,
        canonicalUrl = entity.canonicalUrl,
        lang = entity.lang,
        themeColor = entity.themeColor,
        metaSources = entity.metaSources,
        fetchLayer = entity.fetchLayer,
        httpStatus = entity.httpStatus,
        antiCrawler = entity.antiCrawler,
        fetchedAt = entity.fetchedAt,
        updateTime = entity.updateTime,
    )
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
    /**
     * 挂在站点层还是页面层。
     *
     * 后台必须能看到这一列：图标正常归 SITE，只有当这一页被判成「同域下的另一个产品」
     * （见 `AssetRolePolicy.divergesFromSite`）时才会有 PAGE 层的 FAVICON/LOGO。
     * 不下发的话，"这一页为什么用了跟隔壁不一样的图标"在后台无从查起。
     */
    @field:Schema(description = "归属层级：SITE=全站共享，PAGE=这一页自己的") var ownerType: AssetOwnerType = AssetOwnerType.SITE,
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

/**
 * 管理后台的站点(域名)视图 —— 一个域名一行。
 *
 * 与 [BookmarkAdminVO] 是**两个层**而不是详略两版：那边一行是一个页面（同域名下 1000 个
 * 视频就是 1000 行），这边一行是一个域名，承载 brandName / NSFW / 域名活性 / 图标这些
 * 「换个页面也不会变」的事实。后台想回答"我们一共收录了多少个站、哪些站需要人工过一遍"
 * 只能在这一层看，在页面层看会被同站的深链淹没。
 *
 * 刻意**不用** BeanUtil 整体拷贝：`SiteEntity` 上一半的字段挂着 `@JsonIgnore`（那是给
 * 前台接口用的），而后台恰恰要看那一半（lastCheckAt / consecutiveFail / nsfwReason）。
 */
data class SiteAdminVO(
    @field:Schema(description = "站点ID") var id: String = "",
    @field:Schema(description = "域名(含端口)") var host: String = "",
    @field:Schema(description = "基础HTTP协议") var scheme: String = "https",
    @field:Schema(description = "站点首页地址") var rootUrl: String = "",
    @field:Schema(description = "链接类型(域名/本地/IP/其他)") var linkType: BookmarkLinkType = BookmarkLinkType.OTHER,

    @field:Schema(description = "站点全名(og:site_name / manifest.name)") var brandName: String? = null,
    @field:Schema(description = "站点短名(manifest.short_name)") var shortName: String? = null,
    @field:Schema(description = "展示用站点名：短名→全名→域名") var displayName: String = "",

    @field:Schema(description = "疑似涉黄/涉赌等违规内容(NSFW)") var nsfw: Boolean = false,
    @field:Schema(description = "NSFW 判定理由(CLEAN 表示判过且干净)") var nsfwReason: String? = null,

    @field:Schema(description = "域名是否可达") var isAlive: Boolean = true,
    @field:Schema(description = "上次域名探测时间(不论结论)") var lastCheckAt: LocalDateTime? = null,
    @field:Schema(description = "下次域名巡检时间") var nextCheckAt: LocalDateTime? = null,
    @field:Schema(description = "连续探测失败次数") var consecutiveFail: Int = 0,

    @field:Schema(description = "人工认证：品牌名与图标已核对，抓取不再覆盖") var verifyFlag: Boolean = false,
    @field:Schema(description = "被人工锁定、不会被自动抓取覆盖的字段") var lockedFields: List<SiteLockedField> = emptyList(),

    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "最近更新时间") var updateTime: LocalDateTime? = null,

    /* 下面三块靠批量回填，不来自 site 表本身 */
    @field:Schema(description = "该站点下已收录的页面数") var pageCount: Int = 0,
    /**
     * 该站点下页面按抓取状态的分布，`pageCount` 就是它的和。
     *
     * 存在的理由：光有 `pageCount` 只能回答「这个站有多大」，回答不了「这个站烂不烂」——
     * 而后台列表的用途从来是后者。有了分布，站点行才能画出健康分段条、才能按异常数排序，
     * 也才谈得上「不下钻就知道要不要下钻」。
     *
     * 与 `pageCount` 出自**同一条** `group by (site_id, parse_status)`，不额外增加查询。
     */
    @field:Schema(description = "该站点下页面按抓取状态的分布(键为 ParseStatusEnum)")
    var pageStatusCounts: Map<ParseStatusEnum, Int> = emptyMap(),
    @field:Schema(description = "该站点的全部图标资产(favicon/logo，已签名)") var assets: List<SiteAssetAdminVO> = emptyList(),
) {
    constructor(entity: SiteEntity) : this(
        id = entity.id,
        host = entity.host,
        scheme = entity.scheme,
        rootUrl = entity.rootUrl,
        linkType = entity.linkType,
        brandName = entity.brandName,
        shortName = entity.shortName,
        displayName = entity.displayName,
        nsfw = entity.nsfw,
        nsfwReason = entity.nsfwReason,
        isAlive = entity.isAlive,
        lastCheckAt = entity.lastCheckAt,
        nextCheckAt = entity.nextCheckAt,
        consecutiveFail = entity.consecutiveFail,
        verifyFlag = entity.verifyFlag,
        lockedFields = entity.lockedFieldSet.toList(),
        createTime = entity.createTime,
        updateTime = entity.updateTime,
    )

    /**
     * 写入按状态分布的页面统计，并把 [pageCount] 同步成它的和。
     *
     * 两个字段必须一起赋值，否则会出现「总数 8 但分布加起来是 5」这种自相矛盾的行 ——
     * 分开赋值就是迟早会漏一个，所以这里不提供只改其一的入口。
     */
    fun applyPageStats(counts: Map<ParseStatusEnum, Int>) {
        pageStatusCounts = counts
        pageCount = counts.values.sum()
    }
}

/**
 * 管理后台：某展示模式下**规则实际选出**的渲染结果，纯只读。
 *
 * 前身是 `SiteDisplayPrefVO`，那时它混着「人工设置」（内边距/背景色/钉图）与「渲染结果」两件事。
 * 人工设置那半边随 `site_display_pref` 一并移除（2026-08-17），留下的这半边不来自任何偏好表，
 * 而是 [SiteAssetResolver] 现算的 —— 它回答的是「这个书签在 TILE / LIST 下实际会渲染成什么」，
 * 正是排图标规则时最需要的那个事实，所以读侧一并保留。
 */
data class IconRenderVO(
    @field:Schema(description = "展示模式") var displayMode: DisplayMode = DisplayMode.TILE,
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
    @field:Schema(description = "HTTP状态码(scrapper 回给我方的，不是目标站点的)") var httpStatus: Int? = null,
    @field:Schema(description = "scrapper 错误码，成功时为空") var errorCode: String? = null,
    /**
     * 目标站点自己的最终状态码。与 [httpStatus] 是两个问题：那一列是 scrapper 回给我方的码，
     * `FETCH_FAILED` 恒 502、`TIMEOUT` 恒 504，看不出目标站点发生了什么。
     */
    @field:Schema(description = "目标站点的最终HTTP状态码，未连上目标时为空") var targetStatus: Int? = null,
    @field:Schema(description = "命中来源") var source: String? = null,
    @field:Schema(description = "是否命中scrapper缓存") var cached: Boolean? = null,
    @field:Schema(description = "实际抓取层：HTTP/HEADLESS/SITE_API") var layerUsed: String? = null,
    @field:Schema(description = "耗时(ms)") var durationMs: Long = 0,
    @field:Schema(description = "错误信息") var errorMsg: String? = null,
    @field:Schema(description = "调用时间") var createTime: LocalDateTime = LocalDateTime.now(),
    /**
     * 该域名的站点图标（我方 OSS 签名地址）。
     *
     * 日志表本身不存图标，这一列是按 `urlHost` 反查站点资产补上的，为空是常态 ——
     * 我方从没成功抓到过这个站的图标时就没有，前端应当落本地兜底图。
     * **不要**在前端拿 host 拼 `https://<host>/favicon.ico` 来填这个空：那是直连外站。
     *
     * 注意它不参与上面的 [BeanUtil.copyProperties]（源实体没有这个属性），必须由
     * 调用方显式赋值。
     */
    @field:Schema(description = "站点图标签名地址，我方无此站图标时为空") var faviconUrl: String? = null,
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

/**
 * 失败站点排行的一行：某个域名在时间窗内的抓取失败画像。
 *
 * # 这张表回答什么
 *
 * 「要不要给某类站点建一个永不重试的禁访名单」这个问题，之前只能靠印象回答 —— `scrapper_call_log`
 * 是一次调用一行，翻它只看得到个例。而做不做名单取决于两个数：**这个站点失败得有多频繁**，
 * 以及**每次失败烧掉多少秒**。后者才是真正的代价：一次无头浏览器的失败要 30 秒，而一次 DNS
 * 失败只要几百毫秒，同样是"失败 10 次"，两者差了两个数量级。
 *
 * # 为什么不直接给结论
 *
 * 这里刻意只呈现事实，不给「建议屏蔽」之类的判定。同一个高失败率有完全不同的处置：
 * 反爬（403/406/412）应该去写一个站点 API 适配器，把失败变成成功；连不上/DNS 失败是站点
 * 真的没了，交给巡检判失联即可；而 `SCRAPPER_UNREACHABLE` / `HEADLESS_UNAVAILABLE` 压根
 * 不是站点的问题，是我方抖动，按站点归因就是错的。区分它们的是 [errorBreakdown] 和
 * [lastTargetStatus]，不是失败率本身。
 */
data class ScrapperFailedHostVO(
    @field:Schema(description = "域名") var urlHost: String,
    @field:Schema(description = "窗口内总调用次数") var totalCalls: Long,
    @field:Schema(description = "窗口内失败次数") var failedCalls: Long,
    /** 失败落在多少个不同的地址上。1 表示只有一个页面在反复失败，接近 [failedCalls] 表示整站都抓不动 */
    @field:Schema(description = "失败涉及的不同URL数") var failedUrls: Long,
    /**
     * 失败调用的累计耗时 —— 这就是"浪费"，也是排行默认的排序依据。
     *
     * 单看失败次数会把一个失败 20 次、每次 200ms 的站点排在失败 7 次、每次 28 秒的前面，
     * 而后者才是真正值得处理的那个（生产上 console.cloud.tencent.com 就是这个形态）。
     */
    @field:Schema(description = "失败调用累计耗时(ms)") var failedDurationMs: Long,
    @field:Schema(description = "全部调用累计耗时(ms)") var totalDurationMs: Long,
    @field:Schema(description = "最近一次失败时间") var lastFailedAt: LocalDateTime? = null,
    /**
     * 窗口内最近一次**成功**的时间，为空表示这个窗口里从来没成功过。
     *
     * 它是判断「这个站点是不是彻底抓不动」的关键：同一个域名根路径 200、内容页 412 是常见形态
     * （B 站就是），此时失败率很高但站点本身完全正常，按域名屏蔽会误伤。
     */
    @field:Schema(description = "窗口内最近一次成功时间，从未成功时为空") var lastSuccessAt: LocalDateTime? = null,
    @field:Schema(description = "最近一次失败的URL") var lastFailedUrl: String? = null,
    @field:Schema(description = "最近一次失败的错误信息") var lastErrorMsg: String? = null,
    @field:Schema(description = "最近一次失败的错误码") var lastErrorCode: String? = null,
    @field:Schema(description = "最近一次失败时目标站点的状态码") var lastTargetStatus: Int? = null,
    /** 最近一次失败停在哪一层。HEADLESS 表示无头浏览器也试过了，重试大概率还是同一个结果 */
    @field:Schema(description = "最近一次失败尝试到的抓取层") var lastLayerUsed: String? = null,
    /** 窗口内该域名各错误码的次数，按次数降序。为空只可能是历史行（迁移前不记错误码） */
    @field:Schema(description = "错误码分布，按次数降序") var errorBreakdown: List<ScrapperErrorCodeCountVO> = emptyList(),
    /** 见 [ScrapperCallLogVO.faviconUrl]：我方 OSS 签名地址，为空时前端落本地兜底图，不要直连外站 */
    @field:Schema(description = "站点图标签名地址，我方无此站图标时为空") var faviconUrl: String? = null,
)

/** 某个错误码在窗口内出现了多少次 */
data class ScrapperErrorCodeCountVO(
    @field:Schema(description = "错误码；迁移前的历史行没有这个值，统一归为 UNKNOWN") var errorCode: String,
    @field:Schema(description = "出现次数") var count: Long,
)

/**
 * 系统配置的一次变更。
 *
 * [changes] 是服务端算好的逐字段差异，不是让前端自己去比两份 JSON：一次保存提交的是整份配置，
 * 而人真正想知道的是「这次动了哪一项」。整份原文仍然一并下发（[oldValue] / [newValue]），
 * 供差异不够用时兜底 —— 比如配置类改过字段名，此时逐字段比对会把它显示成一删一增。
 */
data class ConfigChangeLogVO(
    @field:Schema(description = "记录ID") val id: String,
    @field:Schema(description = "配置键") val configKey: String,
    @field:Schema(description = "本次真正变化的字段") val changes: List<ConfigFieldChangeVO>,
    @field:Schema(description = "是否该组配置的首次写入(此前库中没有这一行)") val initial: Boolean,
    @field:Schema(description = "改动前整份JSON") val oldValue: String? = null,
    @field:Schema(description = "改动后整份JSON") val newValue: String,
    @field:Schema(description = "操作管理员ID") val operatorId: String? = null,
    @field:Schema(description = "操作当时的管理员昵称") val operatorName: String? = null,
    @field:Schema(description = "发生时间") val createTime: LocalDateTime,
)

/** 一个字段的前后取值；[oldValue] 为 null 且 [initial] 时表示这一项此前不存在 */
data class ConfigFieldChangeVO(
    @field:Schema(description = "字段名(配置类的属性名)") val field: String,
    @field:Schema(description = "旧值(JSON 标量的字符串形式)") val oldValue: String? = null,
    @field:Schema(description = "新值") val newValue: String? = null,
)

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
    @field:Schema(description = "书签ID") var pageId: String,
    @field:Schema(description = "URL host") var urlHost: String,
    /**
     * 被探测页面的完整地址（`page.raw_url`），由 Service 层按 pageId 批量补，页面已删除时为 null。
     *
     * 日志表本身只存 host。按 pageId 查单页历史时那样够用，但按巡检轮次下钻时一屏里会有同一域名下的
     * 几十条深链，只给 host 完全分不清是哪一页 —— 而「首页 ALIVE、某条深链 DEAD」正是最常见的形态。
     */
    @field:Schema(description = "被探测页面的完整地址；页面已删除时为空") var url: String? = null,
    @field:Schema(description = "探测结论(ALIVE/DEAD/UNKNOWN)") var outcome: PingOutcome,
    @field:Schema(description = "是否存活；结论为 UNKNOWN 时为 null") var alive: Boolean? = null,
    @field:Schema(description = "ping通后是否触发了重新解析") var triggeredParse: Boolean = false,
    @field:Schema(description = "产生这次探测的巡检轮次(sweep_log.id)；历史行为空") var sweepId: String? = null,
    @field:Schema(description = "检查时间") var createTime: LocalDateTime = LocalDateTime.now(),
) {
    constructor(entity: PagePingLogEntity) : this(
        id = entity.id,
        pageId = entity.pageId,
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

/**
 * 管理后台分享详情里的单条书签。
 *
 * 刻意不直接下发 [BookmarkShow]：审核这件事要看的恰恰是它 `@JsonIgnore` 掉的 `nsfw` ——
 * 那个字段对前台是内部判定，对后台是「这条分享为什么该拦」的唯一依据。
 */
data class ShareAdminBookmarkVO(
    @field:Schema(description = "书签ID(bookmark.id)") var bookmarkId: String? = null,
    @field:Schema(description = "页面ID(page.id)") var pageId: String? = null,
    @field:Schema(description = "标题(按 TILE 模式解析后的最终文案)") var title: String? = null,
    @field:Schema(description = "描述") var description: String? = null,
    @field:Schema(description = "完整URL") var urlFull: String? = null,
    @field:Schema(description = "图标签名地址；走首字母色块时为 null") var iconUrl: String? = null,
    @field:Schema(description = "链接类型(域名/本地/IP/其他)") var linkType: BookmarkLinkType = BookmarkLinkType.OTHER,
    @field:Schema(description = "站点是否被判定为疑似违规(NSFW)") var nsfw: Boolean = false,
) {
    constructor(show: BookmarkShow) : this(
        bookmarkId = show.bookmarkId,
        pageId = show.pageId,
        title = show.title,
        description = show.description,
        urlFull = show.urlFull,
        iconUrl = show.logo.url,
        linkType = show.linkType,
        nsfw = show.nsfw,
    )
}

/** 管理后台分享详情：列表条目的全部字段 + 分享包含的书签 */
data class ShareAdminDetailVO(
    @field:Schema(description = "分享本身的信息") var share: UserShareAdminVO,
    @field:Schema(description = "分享包含的全部书签(按分享内排序)") var bookmarks: List<ShareAdminBookmarkVO> = emptyList(),
)

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

/**
 * 巡检健康摘要（后台常驻告警条的数据源）。
 *
 * 熔断的语义是「我方链路坏了，那一轮全表结论不可信」——它此前唯一的出口是一行会滚掉的
 * `log.error`。把它做成一个后台随时看得见的数字，才谈得上"有人知道"。
 */
data class SweepHealthVO(
    @field:Schema(description = "统计窗口(小时)") var windowHours: Int = 24,
    @field:Schema(description = "窗口内的巡检轮次总数") var roundCount: Int = 0,
    @field:Schema(description = "窗口内被熔断中止的轮次数") var breakerCount: Int = 0,
    @field:Schema(description = "窗口内因解析队列拥堵被推迟的重新抓取条数") var deferredParse: Int = 0,
    @field:Schema(description = "最近一次熔断的轮次；窗口内没有熔断时为空") var latestBreaker: SweepLogEntity? = null,
    @field:Schema(
        description = "最近一轮巡检的时间。距今过久说明巡检压根没在跑(调度线程卡死/巡检锁没释放)，" +
            "那种情况下熔断次数恒为 0，只看熔断数看不出来"
    )
    var lastRoundAt: LocalDateTime? = null,
)

/**
 * 手动触发一轮巡检之前的「这一轮会做什么」预览。
 *
 * ## 为什么触发前要先算一遍
 *
 * 这不是一个幂等的只读操作：一轮存活巡检最多打 200 个站点、会把连续失败到阈值的书签**改判为失联**、
 * 还会顺带向解析池投递几十条重新抓取。点之前看不见范围的话，管理员只能靠猜——而这几件事里任何一件
 * 都不是"再点一次就好"的。所以确认框里的每个数字都是这里现算的，与真正开跑时用的是同一套候选查询
 * （同样的状态过滤、同样的 `next_check_at` 游标、同样的 LIMIT 和非域名过滤）。
 *
 * 预览与执行之间当然有时间差（游标会推进、站点活性会变），所以这些数是**预估而非承诺**——
 * 尤其 [probes]：站点层短路的页面若在开跑时发现根地址已恢复，会回到逐页探测，实际探测数会更接近
 * [candidates]。[worstCaseMs] 按那种情况算。
 */
data class SweepPreviewVO(
    @field:Schema(description = "巡检任务(方法名)") var taskLabel: String = "",
    @field:Schema(description = "这个任务管哪一批书签，中文描述") var scope: String = "",
    @field:Schema(description = "到期候选总数，不含 LIMIT 也不含非域名过滤") var backlog: Long = 0,
    @field:Schema(description = "单轮处理上限(LIMIT)") var batchSize: Int = 0,
    @field:Schema(description = "本轮会处理的条数：已按 LIMIT 截断、已滤掉非域名书签") var candidates: Int = 0,
    @field:Schema(description = "被 LIMIT 截断、留到下一轮的条数") var truncated: Long = 0,
    @field:Schema(description = "本地地址/IP 等非域名书签，不探测也不计入候选") var skippedNonDomain: Int = 0,
    @field:Schema(description = "预计被站点层短路(所属域名已判死)、不逐页探测的条数") var shortCircuited: Int = 0,
    @field:Schema(description = "为已判死的域名补探根地址的次数") var rootProbes: Int = 0,
    @field:Schema(description = "预计实际发起的探测次数(逐页 + 根地址)，也是耗时的来源") var probes: Int = 0,
    @field:Schema(description = "预计最多有多少条会顺带触发重新抓取(异步，不计入本轮耗时)") var mayTriggerParse: Int = 0,
    @field:Schema(description = "本任务是否有资格把书签改判为失联") var mayConfirmDeath: Boolean = false,
    @field:Schema(description = "连续失败多少次才落定失联；仅在 mayConfirmDeath 时有意义") var deadConfirmFailures: Int = 0,
    @field:Schema(description = "该任务配置的检测间隔(小时)") var intervalHours: Int = 0,
    @field:Schema(description = "并行探测的并发度") var concurrency: Int = 1,
    @field:Schema(description = "预估耗时(ms)") var estimatedMs: Long = 0,
    @field:Schema(description = "最坏耗时(ms)：全部探测都吃满单条超时") var worstCaseMs: Long = 0,
    @field:Schema(
        description = "预估所用的单条探测平均墙钟耗时(ms)。来自最近若干轮的真实记录，" +
            "为空表示没有历史样本、用的是默认假设"
    )
    var sampleProbeMs: Long? = null,
    @field:Schema(description = "预估依据的历史轮次数；0 表示无样本") var sampleRounds: Int = 0,
    @field:Schema(description = "上一轮是否仍在进行(或另一实例正在跑)。为真时触发会被巡检锁挡下") var running: Boolean = false,
)

/**
 * 手动触发一轮巡检的受理结果。
 *
 * 巡检本身是 `@Async` 的，接口返回时它才刚被投递，所以这里只回答"收下了没有"——
 * [accepted] 为假只有一个原因：巡检锁被占着（上一轮还没跑完，或另一个实例正在跑）。
 * 真正的结果要等那一轮跑完后落进 `sweep_log`。
 */
data class SweepTriggerResultVO(
    @field:Schema(description = "是否已受理并投递") var accepted: Boolean = false,
    @field:Schema(description = "给管理员看的说明") var message: String = "",
)

/**
 * 无人引用书签清理的「会删什么 / 删了什么」。
 *
 * 预览与执行返回的是同一个结构，用 [dryRun] 区分——两者必须由同一段代码算出来，
 * 否则确认框里的数字与真正删掉的东西之间没有任何保证，而这个操作没有撤销路径。
 *
 * 三层计数刻意分开列：[pages] / [sites] 是管理员真正关心的「删掉了多少条记录」，
 * 其余是随之级联清掉的附属行——它们数量大得多（一个页面能带几十条 ping 日志），
 * 混在一起报会让人以为删多了。
 */
data class OrphanCleanupReport(
    @field:Schema(description = "是否只统计不删除") var dryRun: Boolean = true,

    /* ── 页面层 ── */
    @field:Schema(description = "无人引用且属于本地/IP 站点的页面数") var localIpPages: Int = 0,
    @field:Schema(description = "无人引用且已判定失活(抓取失败/已归档)的页面数") var deadPages: Int = 0,
    @field:Schema(description = "实际删除的页面数。两条规则会重叠，所以不等于上面两个相加") var pages: Int = 0,
    @field:Schema(
        description = "命中规则但因创建时间太近而跳过的页面数。" +
            "用户正在添加的书签会先建页面、再建关联，这段窗口里它看起来正是「无人引用」"
    )
    var skippedRecentPages: Int = 0,

    /* ── 站点层 ── */
    @field:Schema(description = "实际删除的站点数(已无任何页面，且是本地/IP 或已判定不可达)") var sites: Int = 0,

    /* ── 级联清掉的附属行 ── */
    @field:Schema(description = "页面元信息(page_meta)") var pageMeta: Int = 0,
    @field:Schema(description = "抓取快照(scrape_snapshot)") var snapshots: Int = 0,
    @field:Schema(description = "探测日志(page_ping_log)") var pingLogs: Int = 0,
    @field:Schema(description = "分类关联(page_category)") var pageCategories: Int = 0,
    @field:Schema(description = "页面级图片资产(社交图/截图)") var pageAssets: Int = 0,
    @field:Schema(description = "站点级图片资产(favicon/logo)") var siteAssets: Int = 0,
    @field:Schema(
        description = "随之失去引用的对象存储文件数。这里**不删对象**，" +
            "它们会在下一轮 OSS 对账里被认定为孤儿后按既有策略回收"
    )
    var releasedFiles: Int = 0,

    @field:Schema(description = "耗时(ms)") var durationMs: Long = 0,
)
