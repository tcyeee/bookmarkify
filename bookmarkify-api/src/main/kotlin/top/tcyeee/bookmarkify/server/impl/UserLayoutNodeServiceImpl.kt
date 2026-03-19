package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.entity.CreateDirParams
import top.tcyeee.bookmarkify.entity.MoveNodeParams
import top.tcyeee.bookmarkify.entity.RenameDirParams
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.NodeTypeEnum
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.UserLayoutNodeMapper
import top.tcyeee.bookmarkify.server.IBookmarkFunctionService
import top.tcyeee.bookmarkify.server.IUserLayoutNodeService
import top.tcyeee.bookmarkify.server.IUserPreferenceService
import top.tcyeee.bookmarkify.utils.SocketUtils

/**
 * 用户桌面排布节点 Service 实现
 *
 * @author tcyeee
 * @date 1/7/26
 */
@Service
class UserLayoutNodeServiceImpl(
    private val preferenceService: IUserPreferenceService,
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper,
    private val bookmarkFunctionService: IBookmarkFunctionService,
) : IUserLayoutNodeService, ServiceImpl<UserLayoutNodeMapper, UserLayoutNodeEntity>() {

    companion object {
        private const val ROOT_ID = "ROOT"
        private const val ROOT_NAME = "ROOT"
    }

    @Transactional
    override fun layout(uid: String): UserLayoutNodeVO {
        // 查询用户的自定义标签
        val bookmarkMap = bookmarkUserLinkMapper.allBookmarkByUid(uid).associateBy { it.layoutNodeId!! }
        // 查询用户的绑定功能
        val bookmarkFunctionMap = bookmarkFunctionService.findByUid(uid).associateBy { it.layoutNodeId }
        // 查询到用户的排序信息
        val sortMap = preferenceService.queryByUid(uid).sortMap
        // 查询到用户布局信息
        return this.findByUid(uid)
            // 格式化为标准桌面标准输出
            .map { it.vo(sortMap[it.id], bookmarkMap[it.id], bookmarkFunctionMap[it.id]) }
            // 重新组织架构
            .let { nodeStructure(it) }
    }

    @Transactional
    override fun createDir(params: CreateDirParams, uid: String): UserLayoutNodeVO {
        // 创建 BOOKMARK_DIR 节点
        val dirNode = UserLayoutNodeEntity(uid = uid, name = params.name, type = NodeTypeEnum.BOOKMARK_DIR)
        save(dirNode)

        // 持久化排序值
        preferenceService.sort(uid, mapOf(dirNode.id to params.sort))

        // 将两个书签节点的父节点更新为新目录
        ktUpdate()
            .`in`(UserLayoutNodeEntity::id, params.nodeIds)
            .eq(UserLayoutNodeEntity::uid, uid)
            .set(UserLayoutNodeEntity::parentId, dirNode.id)
            .update()

        // 构建含子节点的目录 VO 并推送 WebSocket 通知
        val sortMap = preferenceService.queryByUid(uid).sortMap
        val bookmarkMap = bookmarkUserLinkMapper.allBookmarkByUid(uid).associateBy { it.layoutNodeId!! }
        val childVOs = listByIds(params.nodeIds).map { it.vo(sortMap[it.id], bookmarkMap[it.id], null) }
        val dirVO = dirNode.vo(sortMap[dirNode.id], null, null)
        dirVO.children.addAll(childVOs)

        SocketUtils.homeItemUpdate(uid, dirVO)
        return dirVO
    }

    @Transactional
    override fun moveNode(params: MoveNodeParams, uid: String): UserLayoutNodeVO {
        // 记录原父节点，用于移出时推送旧文件夹的更新
        val oldParentId = ktQuery()
            .eq(UserLayoutNodeEntity::id, params.nodeId)
            .eq(UserLayoutNodeEntity::uid, uid)
            .one()?.parentId

        // 更新 parentId（null 表示移到根目录）
        ktUpdate()
            .eq(UserLayoutNodeEntity::id, params.nodeId)
            .eq(UserLayoutNodeEntity::uid, uid)
            .set(UserLayoutNodeEntity::parentId, params.dirNodeId)
            .update()

        val sortMap = preferenceService.queryByUid(uid).sortMap
        val bookmarkMap = bookmarkUserLinkMapper.allBookmarkByUid(uid).associateBy { it.layoutNodeId!! }

        fun buildDirVO(dirId: String): UserLayoutNodeVO {
            val dir = getById(dirId)
            val children = ktQuery()
                .eq(UserLayoutNodeEntity::parentId, dirId)
                .eq(UserLayoutNodeEntity::uid, uid)
                .list()
                .map { it.vo(sortMap[it.id], bookmarkMap[it.id], null) }
            return dir.vo(sortMap[dir.id], null, null).also { it.children.addAll(children) }
        }

        // 移出文件夹：推送旧文件夹（子节点已减少）
        if (oldParentId != null && oldParentId != params.dirNodeId) {
            SocketUtils.homeItemUpdate(uid, buildDirVO(oldParentId))
        }

        // 移入文件夹：推送目标文件夹（子节点已增加）并返回
        // 移到根目录：返回旧文件夹（已在上方推送）
        return if (params.dirNodeId != null) {
            buildDirVO(params.dirNodeId).also { SocketUtils.homeItemUpdate(uid, it) }
        } else {
            buildDirVO(oldParentId!!)
        }
    }

    override fun renameDir(params: RenameDirParams, uid: String): Boolean =
        ktUpdate()
            .eq(UserLayoutNodeEntity::id, params.nodeId)
            .eq(UserLayoutNodeEntity::uid, uid)
            .eq(UserLayoutNodeEntity::type, NodeTypeEnum.BOOKMARK_DIR)
            .set(UserLayoutNodeEntity::name, params.name)
            .update()

    private fun findByUid(uid: String): List<UserLayoutNodeEntity> =
        ktQuery().eq(UserLayoutNodeEntity::uid, uid).list() ?: emptyList()

    /**
     * 组合为层级数据结构,根节点命名为ROOT
     * @param nodeList 没有进行结构化的桌面布局信息, 根节点和子节点信息混合在一起
     * @return 结构化以后的数据, 按照节点与点之间的依赖组成节点树.
     */
    private fun nodeStructure(nodeList: List<UserLayoutNodeVO>): UserLayoutNodeVO {
        // 复制节点以保证 children 独立
        val allNodes = nodeList.associateBy { it.id }
            .mapValues { (_, node) -> node.copy(children = mutableListOf()) }
            .toMutableMap()

        val root = UserLayoutNodeVO(
            id = ROOT_ID,
            parentId = null,
            sort = Int.MIN_VALUE,
            type = NodeTypeEnum.BOOKMARK_DIR,
            name = ROOT_NAME,
            children = mutableListOf()
        )
        allNodes[ROOT_ID] = root

        // 构建父子关系，缺失父节点的挂到根节点
        allNodes.values.forEach { node ->
            if (node.id == ROOT_ID) return@forEach
            val parent = allNodes[node.parentId] ?: root
            parent.children.add(node)
        }

        // 深度排序
        fun sortChildren(current: UserLayoutNodeVO) {
            current.children.sortWith(compareBy<UserLayoutNodeVO> { it.sort }.thenBy { it.name })
            current.children.forEach { sortChildren(it) }
        }
        sortChildren(root)

        return root
    }

}
