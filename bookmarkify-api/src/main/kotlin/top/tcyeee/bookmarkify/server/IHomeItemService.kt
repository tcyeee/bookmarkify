package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.utils.SystemBookmarkStructure

/**
 * @author tcyeee
 * @date 4/14/24 15:38
 */
interface IHomeItemService : IService<HomeItem> {
    fun delete(params: List<String>)

    fun copy(sourceUid: String, targetUid: String): List<HomeItem?>?

    fun deleteOne(id: String): Boolean

    /**
     * 将用户打包上传的书签直接变为临时展示的占位符
     */
    fun chromeBookmarksPackage(structures: List<SystemBookmarkStructure>, uid: String): List<HomeItemShow>
}