package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.HomeItemSortParams
import top.tcyeee.bookmarkify.entity.entity.Bookmark
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
     * 对桌面图标进行排序
     *
     * @param params 仅有ID-SORT
     * @return status
     */
    fun sort(params: List<HomeItemSortParams>)

    fun delete(params: List<String>)

    fun copy(sourceUid: String, targetUid: String): List<HomeItem?>?

    fun deleteOne(id: String): Boolean
}