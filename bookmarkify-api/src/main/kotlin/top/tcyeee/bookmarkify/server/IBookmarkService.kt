package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
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

    /** 解析书签,保存书签到根节点,并通知到用户 */
    fun kafKaBookmarkParseAndNotice(uid: String, bookmarkId: String, parentNodeId: String?)

    /** 解析书签,然后保存到数据库,同时通知到用户 */
    fun kafKaBookmarkParseAndNotice(uid: String, bookmarkId: String, userLinkId: String, nodeId: String)

    /**
     * 通过网址解析为书签,同时重新绑定到添加这个网址的用户
     * 1.解析书签,更新书签状态(之前是LOADING)
     * 2.根据host重新绑定用户自定义书签
     * 3.修改用户布局元素状态(之前是LOADING)
     *
     * 为什么要重新绑定？
     * 答: 用户添加网址的时候是批量添加的,只能提前批量返回用户自定义的书签,用户自定义的书签具体有没有存在源书签还不知道,所以查询完毕知道以后,再重新关联回去
     */
    fun kafkaBookmarkParseAndResetUserItem(uid: String, rawUrl: String, userLinkId: String, layoutNodeId: String)

    /** 根据书签ID解析书签：依据配置决定使用第三方 API 还是内置解析器 */
    fun kafkaBookmarkParse(bookmarkId: String)

    /** 通过 iframely 第三方 API 解析书签元信息并持久化；若书签已通过验证则直接返回已有记录 */
    fun parseBookmarkByApi(bookmark: BookmarkEntity): BookmarkEntity

    /** 批量按 host 域名查询书签列表，用于为新用户初始化默认书签 */
    fun findListByHost(defaultBookmarkify: List<String>): List<BookmarkEntity>

    /** 按 host 域名精确匹配单条书签，不存在时返回 null */
    fun findByHost(host: String): BookmarkEntity?
}
