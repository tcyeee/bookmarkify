package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.entity.entity.NodeTypeEnum
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.UserLayoutNodeMapper
import top.tcyeee.bookmarkify.server.IUserLayoutNodeService
import top.tcyeee.bookmarkify.server.IUserPreferenceService

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
) : IUserLayoutNodeService, ServiceImpl<UserLayoutNodeMapper, UserLayoutNodeEntity>() {

    companion object {
        private const val ROOT_ID = "ROOT"
        private const val ROOT_NAME = "ROOT"
    }

    override fun layout(uid: String): UserLayoutNodeVO {
        // 查询到用户的排序信息
        val sortMap = preferenceService.queryByUid(uid).sortMap
        // 查询布局信息中关联的书签
        val bookmarkMap = bookmarkUserLinkMapper.allBookmarkByUid(uid).associateBy { it.layoutNodeId!! }
        // 查询到用户布局信息
        return this.findByUid(uid)
            .map { it.vo( sortMap[it.id], bookmarkMap[it.id]) }
            .let { nodeStructure(it) }
    }


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
