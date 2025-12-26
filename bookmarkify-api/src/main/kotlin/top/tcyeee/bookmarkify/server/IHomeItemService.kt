package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.entity.HomeItem

/**
 * @author tcyeee
 * @date 4/14/24 15:38
 */
interface IHomeItemService : IService<HomeItem> {

    /**
     * 查看我的桌面元素
     * @param uid user id
     */
    fun findShowByUid(uid: String): List<HomeItemShow>

    /**
     * 临时数据库
     * key   :bookmarkUserLinkId
     * value :BookmarkShow
     *
     * @param uid 用户ID
     * @return database
     */
    fun createDataBaseByUid(uid: String): Map<String, BookmarkShow>

    fun findByUid(uid: String): List<HomeItem>?

    /**
     * 对桌面图标进行排序
     *
     * @param params 仅有ID-SORT
     * @return status
     */
    fun sort(params: List<HomeItem>): Boolean

    fun delete(params: List<String>)

    fun copy(sourceUid: String, targetUid: String)

    fun deleteOne(id: String): Boolean
}