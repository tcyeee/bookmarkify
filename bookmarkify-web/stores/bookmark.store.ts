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
    lastFetchedAt: 0,
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
      this.lastFetchedAt = Date.now()
      console.log(`[DEBUG]桌面布局更新: 根 ${this.order[ROOT_KEY]?.length ?? 0} 项`)
    },

    // 数据是否仍然"新鲜"（缓存非空且在指定时间内拉取过）
    isFresh(withinMs = 2 * 60 * 1000): boolean {
      return (
        this.lastFetchedAt > 0 &&
        Date.now() - this.lastFetchedAt < withinMs &&
        (this.order[ROOT_KEY]?.length ?? 0) > 0
      )
    },

    setLayout(root?: UserLayoutNodeVO | null) {
      const { nodes, order } = normalize(root)
      this.nodes = nodes
      this.order = order
      // 防御：后端不应返回 ≤1 项文件夹，若出现即重大事故 → 报警 + 强制自愈
      this.enforceFolderInvariant()
    },

    // 插入加载占位项到根
    addLoading(node: UserLayoutNodeVO) {
      this.nodes[node.id] = { ...node, type: HomeItemType.BOOKMARK_LOADING, parentId: null, children: undefined }
      this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), node.id]
    },

    // 批量注入导入书签的 LOADING 节点（含文件夹）；避免全量 update()
    addImportLoadingBatch(nodes: UserLayoutNodeVO[]) {
      for (const node of nodes) {
        this.nodes[node.id] = { ...node, parentId: node.parentId ?? null, children: undefined }
        // 为文件夹节点初始化子顺序表
        if (node.type === HomeItemType.BOOKMARK_DIR) {
          this.order[node.id] ??= []
        }
      }
      // 根节点（无 parentId）追加到根列表末尾
      const rootIds = nodes.filter((n) => !n.parentId).map((n) => n.id)
      this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), ...rootIds]
      // 有 parentId 的节点追加到对应文件夹的子顺序列表
      for (const node of nodes) {
        if (node.parentId) {
          this.order[node.parentId] = [...(this.order[node.parentId] ?? []), node.id]
        }
      }
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
      const from = this.parentKeyOf(id)
      delete this.nodes[id]
      for (const k of Object.keys(this.order)) this.order[k] = this.order[k].filter((x) => x !== id)
      delete this.order[id]
      // 删除后源文件夹若 ≤1 项 → 解散，杜绝单项文件夹残留
      if (from && from !== ROOT_KEY && this.nodes[from]?.type === HomeItemType.BOOKMARK_DIR && (this.order[from]?.length ?? 0) <= 1) {
        this.dissolveFolderLocal(from)
      }
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
      // 移出后源文件夹若 ≤1 项 → 正常解散（预期行为，不报警）。这是「绝不出现单项文件夹」的主路径。
      if (from && from !== ROOT_KEY && this.nodes[from]?.type === HomeItemType.BOOKMARK_DIR && (this.order[from]?.length ?? 0) <= 1) {
        this.dissolveFolderLocal(from)
      }
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

    // 解散一个文件夹：残留 0/1 子项并入根末尾、删除文件夹自身。安静执行（不报警）。
    dissolveFolderLocal(folderId: string) {
      const children = this.order[folderId] ?? []
      for (const cid of children) {
        if (this.nodes[cid]) this.nodes[cid] = { ...this.nodes[cid], parentId: null, children: undefined }
      }
      const rootIds = (this.order[ROOT_KEY] ?? []).filter((x) => x !== folderId && !children.includes(x))
      this.order[ROOT_KEY] = [...rootIds, ...children]
      delete this.order[folderId]
      delete this.nodes[folderId]
    },

    // 不变量强制：扫描所有文件夹，任何 ≤1 项的存在都是「重大事故」→ 响亮报警 + 强制解散自愈。
    // 正常操作（moveLocal 移出）已在主路径安静解散，故此处命中即代表出现了非预期的脏状态。
    enforceFolderInvariant(): string[] {
      const bad: string[] = []
      for (const [fid, node] of Object.entries(this.nodes)) {
        if (node?.type !== HomeItemType.BOOKMARK_DIR) continue
        if ((this.order[fid]?.length ?? 0) > 1) continue
        bad.push(fid)
      }
      for (const fid of bad) {
        console.error(
          '%c[重大事故]',
          'color:#fff;background:#dc2626;font-weight:bold;padding:1px 6px;border-radius:3px',
          '检测到 ≤1 项文件夹，已强制解散',
          { folderId: fid, name: this.nodes[fid]?.name, remaining: [...(this.order[fid] ?? [])] },
        )
        this.dissolveFolderLocal(fid)
      }
      return bad
    },
  },

  // persist: true 默认即 localStorage，且不依赖自动注入的 piniaPluginPersistedstate 全局
  // （后者在某些求值时机会报 "not defined"）。与 auth.store 写法一致。
  persist: true,
})
