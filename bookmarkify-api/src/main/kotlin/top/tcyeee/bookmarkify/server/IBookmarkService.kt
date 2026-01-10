package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
import top.tcyeee.bookmarkify.entity.BookmarkSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity

/**
 * @author tcyeee
 * @date 3/10/24 15:45
 */
import com.baomidou.mybatisplus.core.metadata.IPage
import top.tcyeee.bookmarkify.entity.BookmarkAdminVO

interface IBookmarkService : IService<BookmarkEntity> {
    /* 每天检查数据库所有书签活性 */
    fun checkAll()

    fun addOne(url: String, uid: String): UserLayoutNodeVO

    /**
     * 对新用户设置书签
     * @param uid uid
     */
    fun setDefaultBookmark(uid: String)

    fun search(name: String): List<BookmarkEntity>
    fun linkOne(bookmarkId: String, uid: String): UserLayoutNodeVO
    fun allOfMyBookmark(uid: String, params: AllOfMyBookmarkParams): List<BookmarkShow>
    fun importBookmarkFile(file: MultipartFile, uid: String): UserLayoutNodeVO

    fun adminListAll(params: BookmarkSearchParams): IPage<BookmarkAdminVO>
}
