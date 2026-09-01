package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.ApplyReclassifyParams
import top.tcyeee.bookmarkify.entity.CreateDirParams
import top.tcyeee.bookmarkify.entity.MoveNodeParams
import top.tcyeee.bookmarkify.entity.dto.ReclassifyPlan
import top.tcyeee.bookmarkify.entity.RenameDirParams
import top.tcyeee.bookmarkify.entity.UpdateDirColorParams
import top.tcyeee.bookmarkify.entity.UpdateDirCollapsedParams
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.PageEntity
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
     * 修改文件夹颜色；传 null 恢复默认颜色
     */
    fun updateDirColor(params: UpdateDirColorParams, uid: String): Boolean

    /**
     * 修改文件夹折叠状态
     */
    fun updateDirCollapsed(params: UpdateDirCollapsedParams, uid: String): Boolean

    /**
     * 移动书签节点：移入文件夹或移出到根目录
     * @param params 书签节点ID和目标文件夹节点ID（null 表示移到根目录）
     * @param uid 用户ID
     */
    fun moveNode(params: MoveNodeParams, uid: String): UserLayoutNodeVO

    /**
     * 首页「重新归类」· 第一步：让 DeepSeek 把某文件夹里的书签重新分组，返回方案但**不落库**。
     *
     * @param dirNodeId 要重新归类的文件夹节点ID（必须是当前用户的 BOOKMARK_DIR）
     * @throws top.tcyeee.bookmarkify.config.exception.CommonException E102 文件夹不存在/书签太少，E128 书签过多
     */
    fun planReclassify(dirNodeId: String, uid: String): ReclassifyPlan

    /**
     * 首页「重新归类」· 第二步：应用用户确认后的方案（新建文件夹 + 批量移动），一次事务、一次整树推送。
     *
     * 只处理 [ApplyReclassifyParams.groups] 里传来的组（前端已剔除未勾选与「保留」组）。
     * 源文件夹被搬空或只剩 1 项时按 [moveNode] 的既有规则解散。
     */
    fun applyReclassify(params: ApplyReclassifyParams, uid: String): UserLayoutNodeVO

    /**
     * 删除当前用户名下的桌面节点及其关联的自定义书签
     * @param layoutNodeIds 要删除的布局节点 ID 列表
     * @param uid 当前用户 ID（用于权限校验，不属于该用户的节点不会被删除）
     */
    fun deleteByIds(layoutNodeIds: List<String>, uid: String)
}
