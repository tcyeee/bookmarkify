package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity

/**
 * 用户桌面排布节点 Service
 *
 * @author tcyeee
 * @date 1/7/26
 */
interface IUserLayoutNodeService : IService<UserLayoutNodeEntity>{
    // TODO 拿到用户桌面布局
    // TODO 根据用户上传的URL生成占位符
    // TODO 根据用户导入的Chrome书签文档批量生成占位符
    // TODO 用户创建新的文件夹结构
    // TODO 用户删除一个文件夹结构
    // TODO 用户对文件结构进行重新排序
}

