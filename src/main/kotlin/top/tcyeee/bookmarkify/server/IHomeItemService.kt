package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.po.HomeItem
import top.tcyeee.bookmarkify.entity.response.BookmarkShow
import top.tcyeee.bookmarkify.entity.response.HomeItemShow

/**
 * @author tcyeee
 * @date 4/14/24 15:38
 */
interface IHomeItemService : IService<HomeItem> {
    fun findShowByUid(uid: String): List<HomeItemShow>

    /**
     * 创建用户数据库
     * key   :bookmarkId
     * value :bookmarkShow
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

    fun delete(params: List<HomeItem>): Boolean

    fun copy(sourceUid: String, targetUid: String)
}