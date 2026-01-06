package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.entity.Bookmark

/**
 * @author tcyeee
 * @date 3/10/24 15:45
 */
interface IBookmarkService : IService<Bookmark> {
    /* 每天检查数据库所有书签活性 */
    fun checkAll()

    fun addOne(url: String, uid: String): HomeItemShow

    /**
     * 对新用户设置书签
     * @param uid uid
     */
    fun setDefaultBookmark(uid: String)

    fun search(name: String): List<Bookmark>
    fun linkOne(bookmarkId: String, uid: String): HomeItemShow
    fun allOfMyBookmark(uid: String, params: AllOfMyBookmarkParams): List<BookmarkShow>
    fun importBookmarkFile(file: MultipartFile, uid: String): List<BookmarkShow>?

    /**
     * 书签检查(必须是异步)
     * ⚠️只允许被Kafka调用,私自调用很可能导致堵塞
     *
     * @param bookmark 原标签
     */
    fun parseBookmark(bookmark: Bookmark)

    /* 找到用户的书签,然后通知到前端用户 */
    fun findBookmarkAndNotice(bookmarkUserLinkId: String, homeItemId: String, uid: String): Unit
}