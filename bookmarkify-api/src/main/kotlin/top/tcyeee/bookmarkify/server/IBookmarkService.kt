package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
import top.tcyeee.bookmarkify.entity.BookmarkBasicInfoUpdateParams
import top.tcyeee.bookmarkify.entity.BookmarkIconUpdateParams
import top.tcyeee.bookmarkify.entity.BookmarkSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity

/**
 * @author tcyeee
 * @date 3/10/24 15:45
 */
import com.baomidou.mybatisplus.core.metadata.IPage
import top.tcyeee.bookmarkify.entity.BookmarkAdminVO
import top.tcyeee.bookmarkify.entity.BookmarkLivenessVO
import top.tcyeee.bookmarkify.entity.BookmarkRefetchApplyParams
import top.tcyeee.bookmarkify.entity.BookmarkRefetchVO
import top.tcyeee.bookmarkify.entity.BookmarkSearchVO
import top.tcyeee.bookmarkify.entity.CategoryVO
import top.tcyeee.bookmarkify.entity.BookmarkImportPreviewVO
import top.tcyeee.bookmarkify.entity.dto.ScrapeResponse
import top.tcyeee.bookmarkify.entity.dto.SimilarSite

interface IBookmarkService : IService<BookmarkEntity> {
    /** 每天检查数据库所有书签活性 */
    fun checkAll()

    /** 定时重试 UNREACHABLE 书签：ping 通后重新触发解析，结果写入 bookmark_ping_log */
    fun retryUnreachableBookmarks()

    /** 每小时扫描一次全部 7 天未更新的书签（含已认证）做活性检查，结果写入 bookmark_ping_log */
    fun livenessCheckStaleBookmarks()

    /** 添加书签并异步检查 */
    fun addOne(url: String, uid: String): UserLayoutNodeVO

    /** 为新用户设置默认书签 */
    fun setDefaultBookmark(uid: String)

    /** 为新用户设置默认功能 */
    fun setDefaultFunction(uid: String)

    /** 搜索书签（用户端「添加」搜索，返回含小图标的精简结果） */
    fun search(name: String): List<BookmarkSearchVO>

    /** 关联一个已验证通过的书签 */
    fun linkOne(bookmarkId: String, uid: String): UserLayoutNodeVO

    /** 查看我的全部书签 */
    fun allOfMyBookmark(uid: String, params: AllOfMyBookmarkParams): IPage<BookmarkShow>

    /** 解析 Chrome 书签 HTML，返回含重复标记的预览列表（不写库） */
    fun previewImport(file: MultipartFile, uid: String): BookmarkImportPreviewVO

    /** 导入 Chrome 书签；skipUrls 为用户选择跳过的完整 URL 集合；返回创建好的 LOADING 占位节点列表 */
    fun importBookmarkFile(file: MultipartFile, uid: String, skipUrls: Set<String> = emptySet()): List<UserLayoutNodeVO>

    /** 管理员查询全部书签 */
    fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO>

    /** 管理员修改书签图标设置（图片内边距 iconPadding、图标背景色 iconBgColor） */
    fun adminUpdateIcon(bookmarkId: String, params: BookmarkIconUpdateParams)

    /** 管理员「重新获取」：重新解析网站标题与图标但不落库，暂存抓取结果供后续应用，返回预览数据 */
    fun adminRefetch(bookmarkId: String): BookmarkRefetchVO

    /** 管理员「书签检测」：直接调用 scrapper 重新抓取一次，回传其给出的全部字段，并同步落库 isActivity/parseStatus */
    fun adminCheckLiveness(bookmarkId: String): BookmarkLivenessVO

    /** 管理员应用「重新获取」的结果：按选择采用新标题/新图标并持久化（采用新图标会重抓高清 LOGO 到 OSS） */
    fun adminApplyRefetch(bookmarkId: String, params: BookmarkRefetchApplyParams): BookmarkAdminVO

    /** 管理员「一键更新」：重新抓取网站信息并直接覆盖持久化标题/简介/图标/高清 LOGO，同步落库 isActivity/parseStatus */
    fun adminRefresh(bookmarkId: String): BookmarkAdminVO

    /**
     * 「网站管理」页任意 URL 活性检测在抓取成功后的落库同步：仅当该 URL 已对应某条 canonical 书签(按
     * urlHost+urlPath 匹配)时才生效，覆盖持久化标题/简介/图标/高清 LOGO，同步落库 isActivity/parseStatus；
     * 未命中已有书签时不新建记录，也不落库。返回是否实际同步了某条书签。
     */
    fun adminSyncFromExternalScrape(url: String, vo: ScrapeResponse): Boolean

    /** 管理员手动编辑书签基础信息（标题/简介），非空字段才会覆盖 */
    fun adminUpdateBasicInfo(bookmarkId: String, params: BookmarkBasicInfoUpdateParams): BookmarkAdminVO

    /** 管理员手动设置某书签的分类（覆盖式），返回更新后的分类列表 */
    fun adminUpdateCategories(bookmarkId: String, categoryIds: List<String>): List<CategoryVO>

    /** 管理员对某书签重新跑一次 DeepSeek 自动归类，返回更新后的分类列表 */
    fun adminRecategorize(bookmarkId: String): List<CategoryVO>

    /** 管理员：AI 推荐与该书签相似的网站（仅展示，回填 exists 标记本地是否已收录） */
    fun adminSimilarSites(bookmarkId: String): List<SimilarSite>

    /** 管理员「一键收录」：异步顺序收录相似网站域名，逐站通过 WebSocket 回推进度（抓取失败=幻觉，删除并跳过） */
    fun adminIngestSimilar(adminUid: String, domains: List<String>)

    fun adminGenerateAppName(bookmarkId: String): String?

    /** 解析书签,然后保存到数据库,同时通过 WebSocket 通知用户（异步事件入口） */
    fun parseAndNotice(uid: String, bookmarkId: String, userLinkId: String, nodeId: String)

    /**
     * 通过网址解析为书签,同时重新绑定到添加这个网址的用户（异步事件入口）
     * 1.解析书签,更新书签状态(之前是LOADING)
     * 2.根据host重新绑定用户自定义书签
     * 3.修改用户布局元素状态(之前是LOADING)
     *
     * 为什么要重新绑定？
     * 答: 用户添加网址的时候是批量添加的,只能提前批量返回用户自定义的书签,用户自定义的书签具体有没有存在源书签还不知道,所以查询完毕知道以后,再重新关联回去
     */
    fun parseAndResetUserItem(uid: String, rawUrl: String, userLinkId: String, layoutNodeId: String)

    /** 根据书签ID解析书签并保存：依据配置决定使用远程 scrapper 还是内置解析器（异步事件入口） */
    fun parseAndSave(bookmarkId: String)

    /** 通过 scrapper 远程解析书签元信息并持久化；若书签已通过验证则直接返回已有记录 */
    fun parseBookmarkByApi(bookmark: BookmarkEntity): BookmarkEntity

    /**
     * 批量按 host 域名查询书签列表（忽略路径，一个 host 现在可能对应多条不同路径的 canonical 记录）。
     * 用于「该域名下是否已收录过任意页面」这类域名级别的存在性判断（如相似站点推荐去重）。
     */
    fun findListByHost(defaultBookmarkify: List<String>): List<BookmarkEntity>

    /** 按 host 域名匹配任意一条书签（忽略路径），不存在时返回 null；用于域名级别的存在性判断 */
    fun findByHost(host: String): BookmarkEntity?

    /**
     * 管理员「一键分类」批量检查全部 canonical 书签是否 NSFW(涉黄/涉赌等违规内容)，命中的直接回写 nsfw=true。
     * 只会把 false 改成 true，不会清除已有的 nsfw 标记（避免 LLM 判断不稳定导致反复摇摆）。
     * @return (本次扫描的书签总数, 新命中 NSFW 的书签数)
     */
    fun checkNsfwForAll(): Pair<Int, Int>

    /** 批量按完整 URL（host+path）精确匹配书签列表，用于为新用户初始化默认书签 */
    fun findListByUrl(urls: List<String>): List<BookmarkEntity>
}
