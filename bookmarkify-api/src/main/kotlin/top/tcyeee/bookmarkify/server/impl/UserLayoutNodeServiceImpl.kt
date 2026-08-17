package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.CreateDirParams
import top.tcyeee.bookmarkify.entity.MoveNodeParams
import top.tcyeee.bookmarkify.entity.RenameDirParams
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.NodeTypeEnum
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity
import top.tcyeee.bookmarkify.entity.enums.DisplayMode
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.UserLayoutNodeMapper
import top.tcyeee.bookmarkify.server.ILayoutNodeFunctionService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.IUserLayoutNodeService
import top.tcyeee.bookmarkify.server.IUserPreferenceService
import top.tcyeee.bookmarkify.server.asset.IconResolver
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
    private val bookmarkUserLinkMapper: BookmarkMapper,
    private val bookmarkUserLinkService: IBookmarkUserLinkService,
    private val layoutNodeFunctionService: ILayoutNodeFunctionService,
    private val iconResolver: IconResolver,
) : IUserLayoutNodeService, ServiceImpl<UserLayoutNodeMapper, UserLayoutNodeEntity>() {

    companion object {
        private const val ROOT_ID = "ROOT"
        private const val ROOT_NAME = "ROOT"

        /**
         * 同一层子节点的排列顺序。
         *
         * **凡是把子节点下发给前端的地方都必须用它**，无论走的是 [layout] 这条全量路径，还是
         * [createDir] / [moveNode] 推的那条 HOME_DIR_UPDATE 单文件夹路径。此前只有前者排了序，
         * 后两者直接把 `ktQuery().list()` 的结果原样塞进 `children` —— 那是 Postgres 的堆序，
         * 与用户排好的顺序毫无关系：行被 UPDATE 过（建夹、移入移出都会 UPDATE `parent_id`）之后
         * 新版本元组落在堆的另一处，扫描顺序就此与 `sort` 脱钩。生产库里已经能查到这样的文件夹。
         * 前端 `replaceFolder()` 拿这份 children 整体覆盖该文件夹的顺序表，于是「从文件夹 A 拖一条
         * 书签进文件夹 B」会顺带把 B 里原有书签的顺序打乱成堆序。
         */
        private val CHILD_ORDER = compareBy<UserLayoutNodeVO> { it.sort }.thenBy { it.name }
    }

    /**
     * 用户全部书签的展示视图，按 layoutNodeId 索引。
     *
     * 桌面现在是「小图 + 全名」的列表形态（`pages/index.vue`），故按 [DisplayMode.LIST] 解析图标与文案；
     * 一次批量解析避免逐个书签查资产表。
     */
    private fun bookmarkShowMap(uid: String): Map<String, BookmarkShow> {
        val shows = bookmarkUserLinkMapper.allBookmarkByUid(uid)
        // decorate 同时给出置顶区磁贴那一份图标（BookmarkShow.tileLogo）：同一棵树在首页被渲染
        // 两次，置顶区是 56px 的大图位，拿 LIST 那份会糊。取数只做一次，纯函数跑两遍
        return iconResolver.decorate(shows, DisplayMode.LIST).associateBy { it.layoutNodeId!! }
    }

    @Transactional
    override fun layout(uid: String): UserLayoutNodeVO {
        // 查询用户的自定义标签
        val bookmarkMap = bookmarkShowMap(uid)
        // 查询用户的绑定功能
        val bookmarkFunctionMap = layoutNodeFunctionService.findByUid(uid).associateBy { it.layoutNodeId }
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
        val bookmarkMap = bookmarkShowMap(uid)
        val childVOs = listByIds(params.nodeIds).map { it.vo(sortMap[it.id], bookmarkMap[it.id], null) }.sortedWith(CHILD_ORDER)
        val dirVO = dirNode.vo(sortMap[dirNode.id], null, null)
        dirVO.children.addAll(childVOs)

        SocketUtils.homeDirUpdate(uid, dirVO)
        return dirVO
    }

    @Transactional
    override fun moveNode(params: MoveNodeParams, uid: String): UserLayoutNodeVO {
        // 校验目标文件夹归属：必须是当前用户的 BOOKMARK_DIR；null 表示根目录，无需校验
        if (params.dirNodeId != null) {
            ktQuery()
                .eq(UserLayoutNodeEntity::id, params.dirNodeId)
                .eq(UserLayoutNodeEntity::uid, uid)
                .eq(UserLayoutNodeEntity::type, NodeTypeEnum.BOOKMARK_DIR)
                .one() ?: throw CommonException(ErrorType.E102, "目标文件夹不存在或无权访问")
        }

        // 记录原父节点，用于移出时处理旧文件夹
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

        // F-10: Re-verify the target folder still exists after the move.
        // Under READ COMMITTED isolation there is a TOCTOU window between the ownership check
        // above and this update; a concurrent delete could remove the folder in between.
        // The re-check is inside the same @Transactional context so a failure rolls back.
        if (params.dirNodeId != null) {
            ktQuery()
                .eq(UserLayoutNodeEntity::id, params.dirNodeId)
                .eq(UserLayoutNodeEntity::uid, uid)
                .one() ?: throw CommonException(ErrorType.E102, "目标文件夹在操作过程中被删除，操作已回滚")
        }

        // 节点离开了某个文件夹，检查该文件夹剩余子节点数
        if (oldParentId != null && oldParentId != params.dirNodeId) {
            val remaining = ktQuery()
                .eq(UserLayoutNodeEntity::parentId, oldParentId)
                .eq(UserLayoutNodeEntity::uid, uid)
                .list()

            when {
                remaining.isEmpty() -> {
                    // 文件夹已为空：直接删除，避免遗留空文件夹
                    removeById(oldParentId)
                    // 文件夹整个消失了，单点更新表达不了这个变化，推整棵树让前端重置
                    return layout(uid).also { SocketUtils.homeLayoutRefresh(uid, it) }
                }
                remaining.size == 1 -> {
                    // 文件夹仅剩一个节点：将其移到根目录，继承文件夹的 sort，然后删除文件夹
                    val lastChild = remaining.first()
                    val folderSort = preferenceService.queryByUid(uid).sortMap[oldParentId]
                    ktUpdate()
                        .eq(UserLayoutNodeEntity::id, lastChild.id)
                        .eq(UserLayoutNodeEntity::uid, uid)
                        .set(UserLayoutNodeEntity::parentId, null)
                        .update()
                    if (folderSort != null) {
                        preferenceService.sort(uid, mapOf(lastChild.id to folderSort))
                    }
                    removeById(oldParentId)
                    // 同上：文件夹被解散、子节点升到根，整树刷新
                    return layout(uid).also { SocketUtils.homeLayoutRefresh(uid, it) }
                }
            }
        }

        val sortMap = preferenceService.queryByUid(uid).sortMap
        val bookmarkMap = bookmarkShowMap(uid)

        fun buildDirVO(dirId: String): UserLayoutNodeVO {
            val dir = getById(dirId)
            val children = ktQuery()
                .eq(UserLayoutNodeEntity::parentId, dirId)
                .eq(UserLayoutNodeEntity::uid, uid)
                .list()
                .map { it.vo(sortMap[it.id], bookmarkMap[it.id], null) }
                .sortedWith(CHILD_ORDER)
            return dir.vo(sortMap[dir.id], null, null).also { it.children.addAll(children) }
        }

        // 旧文件夹剩余 ≥ 2 个节点时推送其更新。**仅限"移入另一个文件夹"**：
        // 移到根目录那条路要整树刷新（见下），单推一条 dirUpdate 反而有害。
        if (oldParentId != null && params.dirNodeId != null && oldParentId != params.dirNodeId) {
            SocketUtils.homeDirUpdate(uid, buildDirVO(oldParentId))
        }

        // 三种情形：移入文件夹 / 仅从一个文件夹移到根目录 / 根→根（无结构变化）
        return when {
            params.dirNodeId != null -> buildDirVO(params.dirNodeId).also { SocketUtils.homeDirUpdate(uid, it) }
            // 移出到根目录：变化发生在**两处**（旧文件夹少一个、根多一个），而 HOME_DIR_UPDATE
            // 只表达得了前者。只推它，其他端会把这个 id 从文件夹的顺序表里摘掉却不知道它去了哪，
            // 于是那条书签在别的标签页/设备上直接消失，要刷新才回来。整树刷新是唯一说得清的表达。
            oldParentId != null -> buildDirVO(oldParentId).also { SocketUtils.homeLayoutRefresh(uid, layout(uid)) }
            else -> layout(uid)
        }
    }

    @Transactional
    override fun deleteByIds(layoutNodeIds: List<String>, uid: String) {
        if (layoutNodeIds.isEmpty()) return
        // Filter to only nodes owned by this user to prevent cross-user deletion.
        val owned = ktQuery()
            .`in`(UserLayoutNodeEntity::id, layoutNodeIds)
            .eq(UserLayoutNodeEntity::uid, uid)
            .list()
        if (owned.isEmpty()) return
        owned.forEach { node ->
            // F-03: Cascade into BOOKMARK_DIR children before deleting the folder itself.
            // Without this, child nodes survive with a dangling parentId and silently
            // re-surface at root level on the next layout() call.
            if (node.type == NodeTypeEnum.BOOKMARK_DIR) {
                val childIds = ktQuery()
                    .eq(UserLayoutNodeEntity::parentId, node.id)
                    .eq(UserLayoutNodeEntity::uid, uid)
                    .list()
                    .map { it.id }
                if (childIds.isNotEmpty()) deleteByIds(childIds, uid)
            }
            removeById(node.id)
            bookmarkUserLinkService.deleteOneByNodeId(node.id, uid)
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
            current.children.sortWith(CHILD_ORDER)
            current.children.forEach { sortChildren(it) }
        }
        sortChildren(root)

        return root
    }

}
