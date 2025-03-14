package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.po.Bookmark

/**
 * @author tcyeee
 * @date 3/10/24 15:45
 */
interface IBookmarkService : IService<Bookmark> {
    /**
     * 检查书签信息
     *
     * @param bookmark 检查的书签
     */
    fun checkOne(bookmark: Bookmark)

    /* 每天检查数据库所有书签活性 */
    fun checkAll()

    fun addOne(url: String, uid: String)

    /* 将网站标记为为离线 */
    fun offline(bookmark: Bookmark)
}