import { defineStore } from 'pinia'
import { HomeItemType, ROOT_KEY, type UserLayoutNodeVO } from '@typing'
import { bookmarksShowAll } from '@api'

/** 后端树 → 扁平 { nodes, order }。nodes 不保留 children（归属/顺序唯一来源是 order）。 */
function normalize(root?: UserLayoutNodeVO | null) {
  const nodes: Record<string, UserLayoutNodeVO> = {}
  const order: Record<string, string[]> = { [ROOT_KEY]: [] }
  const walk = (list: Array<UserLayoutNodeVO> | undefined, parentKey: string) => {
    order[parentKey] = []
    const sorted = (list ?? []).slice().sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))
    for (const n of sorted) {
      if (!n?.id) continue
      nodes[n.id] = { ...n, parentId: parentKey === ROOT_KEY ? null : parentKey, children: undefined }
      order[parentKey].push(n.id)
      if (n.type === HomeItemType.BOOKMARK_DIR) walk(n.children, n.id)
    }
  }
  walk(root?.children, ROOT_KEY)
  return { nodes, order }
}

export const useBookmarkStore = defineStore('homeItems', {
  state: () => ({
    nodes: {} as Record<string, UserLayoutNodeVO>,
    order: { [ROOT_KEY]: [] } as Record<string, string[]>,
  }),

  getters: {
    // 文件夹节点即时填充 children，供 cell/Folder.vue 预览图与文件夹浮层使用
    rootNodes(state): Array<UserLayoutNodeVO> {
      return (state.order[ROOT_KEY] ?? [])
        .map((id) => {
          const n = state.nodes[id]
          if (!n) return null
          if (n.type === HomeItemType.BOOKMARK_DIR) {
            const children = (state.order[id] ?? []).map((cid) => state.nodes[cid]).filter(Boolean) as UserLayoutNodeVO[]
            return { ...n, children }
          }
          return n
        })
        .filter(Boolean) as Array<UserLayoutNodeVO>
    },
    childrenOf(state) {
      return (folderId: string): Array<UserLayoutNodeVO> =>
        (state.order[folderId] ?? []).map((id) => state.nodes[id]).filter(Boolean) as Array<UserLayoutNodeVO>
    },
    parentKeyOf(state) {
      return (id: string): string | null => {
        for (const [k, ids] of Object.entries(state.order)) if (ids.includes(id)) return k
        return null
      }
    },
  },

  actions: {
    async update(): Promise<void> {
      const res = await bookmarksShowAll()
      this.setLayout(res)
      console.log(`[DEBUG]桌面布局更新: 根 ${this.order[ROOT_KEY]?.length ?? 0} 项`)
    },

    setLayout(root?: UserLayoutNodeVO | null) {
      const { nodes, order } = normalize(root)
      this.nodes = nodes
      this.order = order
    },

    // 插入加载占位项到根
    addLoading(node: UserLayoutNodeVO) {
      this.nodes[node.id] = { ...node, type: HomeItemType.BOOKMARK_LOADING, parentId: null, children: undefined }
      this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), node.id]
    },

    // 新增已就绪书签到根（AddOneDialog 关联/添加成功且已带 typeApp）
    addNode(node: UserLayoutNodeVO) {
      this.nodes[node.id] = { ...node, parentId: null, children: undefined }
      this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), node.id]
    },

    // WebSocket 就地内容替换（LOADING→BOOKMARK）；仅改内容，保留归属，靠响应式重渲染
    replaceContent(node: UserLayoutNodeVO) {
      if (node.type !== HomeItemType.BOOKMARK || node.typeApp == null) return
      const cur = this.nodes[node.id]
      if (!cur) return
      this.nodes[node.id] = { ...node, parentId: cur.parentId, children: undefined }
    },

    removeNode(id: string) {
      delete this.nodes[id]
      for (const k of Object.keys(this.order)) this.order[k] = this.order[k].filter((x) => x !== id)
      delete this.order[id]
    },

    reorderLocal(parentKey: string, ids: Array<string>) {
      this.order[parentKey] = [...ids]
    },

    moveLocal(id: string, toParentKey: string, index: number) {
      const from = this.parentKeyOf(id)
      if (from) this.order[from] = this.order[from].filter((x) => x !== id)
      const next = [...(this.order[toParentKey] ?? [])]
      next.splice(Math.max(0, Math.min(index, next.length)), 0, id)
      this.order[toParentKey] = next
      if (this.nodes[id]) this.nodes[id] = { ...this.nodes[id], parentId: toParentKey === ROOT_KEY ? null : toParentKey }
    },

    // 本地建夹：folderNode 为后端返回的真实文件夹节点；从根移除两子、文件夹落在 index、子顺序 [target, dragged]
    createFolderLocal(folderNode: UserLayoutNodeVO, draggedId: string, targetId: string, index: number) {
      this.nodes[folderNode.id] = { ...folderNode, parentId: null, children: undefined }
      this.nodes[draggedId] = { ...this.nodes[draggedId], parentId: folderNode.id, children: undefined }
      this.nodes[targetId] = { ...this.nodes[targetId], parentId: folderNode.id, children: undefined }
      const root = (this.order[ROOT_KEY] ?? []).filter((x) => x !== draggedId && x !== targetId)
      root.splice(Math.max(0, Math.min(index, root.length)), 0, folderNode.id)
      this.order[ROOT_KEY] = root
      this.order[folderNode.id] = [targetId, draggedId]
    },

    // moveNode 返回值 reconcile：后端把剩 ≤1 项的文件夹自动解散时，result 是剩余的非文件夹节点。
    // 此处把该文件夹从 order 移除、剩余节点并入根。返回是否发生了解散。
    applyMoveResult(result: UserLayoutNodeVO | null | undefined, srcParentKey: string): boolean {
      if (!result || srcParentKey === ROOT_KEY) return false
      if (result.type === HomeItemType.BOOKMARK_DIR) return false
      // srcParentKey 是被解散的文件夹 id
      const remainingId = result.id
      delete this.order[srcParentKey]
      if (this.nodes[srcParentKey]) delete this.nodes[srcParentKey]
      this.nodes[remainingId] = { ...result, parentId: null, children: undefined }
      this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []).filter((x) => x !== srcParentKey && x !== remainingId), remainingId]
      return true
    },
  },

  persist: {
    storage: piniaPluginPersistedstate.localStorage(),
  },
})
