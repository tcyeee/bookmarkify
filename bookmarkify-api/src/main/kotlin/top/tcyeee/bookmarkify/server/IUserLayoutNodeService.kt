package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity
import top.tcyeee.bookmarkify.utils.SystemBookmarkStructure

/**
 * 用户桌面排布节点
 *
 *
 * @author tcyeee
 * @date 1/7/26
 */
interface IUserLayoutNodeService : IService<UserLayoutNodeEntity> {

    /**
     * 拿到用户全部的桌面布局
     * @param uid uid
     */
    fun layout(uid: String): UserLayoutNodeVO
}

