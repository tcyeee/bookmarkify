package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
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
import top.tcyeee.bookmarkify.entity.BookmarkRefetchApplyParams
import top.tcyeee.bookmarkify.entity.BookmarkRefetchVO

interface IBookmarkService : IService<BookmarkEntity> {
    /** 每天检查数据库所有书签活性 */
    fun checkAll()

    /** 添加书签并异步检查 */
    fun addOne(url: String, uid: String): UserLayoutNodeVO

    /** 为新用户设置默认书签 */
    fun setDefaultBookmark(uid: String)

    /** 为新用户设置默认功能 */
    fun setDefaultFunction(uid: String)

    /** 搜索书签 */
    fun search(name: String): List<BookmarkEntity>

    /** 关联一个已验证通过的书签 */
    fun linkOne(bookmarkId: String, uid: String): UserLayoutNodeVO

    /** 查看我的全部书签 */
    fun allOfMyBookmark(uid: String, params: AllOfMyBookmarkParams): IPage<BookmarkShow>

    /** 导入 Chrome 书签 */
    fun importBookmarkFile(file: MultipartFile, uid: String)

    /** 管理员查询全部书签 */
    fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO>

    /** 管理员修改书签图标设置（图片内边距 iconPadding、图标背景色 iconBgColor） */
    fun adminUpdateIcon(bookmarkId: String, params: BookmarkIconUpdateParams)

    /** 管理员「重新获取」：重新解析网站标题与图标但不落库，暂存抓取结果供后续应用，返回预览数据 */
    fun adminRefetch(bookmarkId: String): BookmarkRefetchVO

    /** 管理员应用「重新获取」的结果：按选择采用新标题/新图标并持久化（采用新图标会重抓高清 LOGO 到 OSS） */
    fun adminApplyRefetch(bookmarkId: String, params: BookmarkRefetchApplyParams): BookmarkAdminVO

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

    /** 批量按 host 域名查询书签列表，用于为新用户初始化默认书签 */
    fun findListByHost(defaultBookmarkify: List<String>): List<BookmarkEntity>

    /** 按 host 域名精确匹配单条书签，不存在时返回 null */
    fun findByHost(host: String): BookmarkEntity?
}
