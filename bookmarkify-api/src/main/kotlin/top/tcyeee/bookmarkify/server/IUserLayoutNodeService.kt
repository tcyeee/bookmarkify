package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.CreateDirParams
import top.tcyeee.bookmarkify.entity.MoveNodeParams
import top.tcyeee.bookmarkify.entity.RenameDirParams
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

    /**
     * 将两个书签节点合并为一个文件夹
     * @param params 文件夹名称和要放入的两个书签节点ID
     * @param uid 用户ID
     */
    fun createDir(params: CreateDirParams, uid: String): UserLayoutNodeVO

    /**
     * 修改文件夹名称
     * @param params 文件夹节点ID和新名称
     * @param uid 用户ID
     */
    fun renameDir(params: RenameDirParams, uid: String): Boolean

    /**
     * 移动书签节点：移入文件夹或移出到根目录
     * @param params 书签节点ID和目标文件夹节点ID（null 表示移到根目录）
     * @param uid 用户ID
     */
    fun moveNode(params: MoveNodeParams, uid: String): UserLayoutNodeVO
}

