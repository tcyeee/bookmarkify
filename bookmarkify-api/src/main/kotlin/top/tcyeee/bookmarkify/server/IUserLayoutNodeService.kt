package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity

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

    /* 用户桌面布局配置 */
    // TODO 用户更新桌面布局配置
    // TODO 拿到用户的全部桌面布局配置数据库

    // TODO 拿到用户全部的桌面布局
//    fun getUserLayout(uid: String): List<UserLayoutNodeVO>

    // TODO 根据用户上传的URL生成占位符
//    fun createPlaceholderFromUrl(uid: String, rawUrl: String): UserLayoutNodeVO

    // TODO 处理用户导入的Chrome书签文档批量生成占位符,返回全部的用户桌面布局
//    fun batchCreatePlaceholdersFromChrome(uid: String, bookmarkFile: InputStream): List<UserLayoutNodeVO>

    // TODO 用户通过拖动叠加两个书签LOGO创建新的文件夹结构(未命名),返回用户的全部桌面布局
//    fun createFolder(uid: String, bookmarkIds: List<String>): UserLayoutNodeEntity

    // TODO 用户删除一个文件夹结构
//    fun deleteFolder(uid: String, folderNodeId: Long): Boolean

}

