import { defineStore } from 'pinia'
import { HomeItemType, ROOT_KEY, type UserLayoutNodeVO } from '@typing'
import { bookmarksShowAll, bookmarksMoveNode, bookmarksDel } from '@api'

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
    // LOADING 节点的兜底定时器句柄（进程内瞬时状态，不落盘，见下方 persist.paths）
    pendingTimeouts: {} as Record<string, ReturnType<typeof setTimeout>>,
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
    // 全部被置顶的书签节点（不区分所在文件夹），沿用书签自身的桌面排序
    pinnedNodes(state): Array<UserLayoutNodeVO> {
      return Object.values(state.nodes)
        .filter((n) => n.type === HomeItemType.BOOKMARK && n.typeApp?.pinned)
        .sort((a, b) => (a.sort ?? 0) - (b.sort ?? 0))
    },
    // 后端 /bookmark/sort 是整表覆盖式写入（非按 key 合并），只提交单个文件夹的顺序会把
    // 其余节点的 sort 一并抹掉。这里始终基于全量 order 生成完整 sort 表，同一列表内的
    // 相对顺序保持不变，供任意拖拽排序场景直接提交。
    fullOrderParams(state): Record<string, number> {
      const params: Record<string, number> = {}
      let i = 0
      for (const ids of Object.values(state.order)) {
        for (const id of ids) params[id] = i++
      }
      return params
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

    // 兜底：解析结果靠 WebSocket 推送，是尽力而为的——连接断开/消息丢失时后端不会重试推送。
    // 超时仍未解除 LOADING 就主动整体重新拉取桌面布局对账，避免节点永远转圈。
    // 调用方应在插入 LOADING 节点后（addLoading / addImportLoadingBatch）为每个节点调用一次。
    watchForResolution(nodeId: string, timeoutMs = 30000) {
      this.clearResolutionWatch(nodeId)
      this.pendingTimeouts[nodeId] = setTimeout(() => {
        delete this.pendingTimeouts[nodeId]
        if (this.nodes[nodeId]?.type === HomeItemType.BOOKMARK_LOADING) {
          console.warn(`[bookmark] 节点 ${nodeId} 超过 ${timeoutMs}ms 未收到解析结果，主动重新拉取桌面布局`)
          this.update()
        }
      }, timeoutMs)
    },

    // 收到 WS 更新 / 节点被手动删除时调用，取消其兜底定时器
    clearResolutionWatch(nodeId: string) {
      const handle = this.pendingTimeouts[nodeId]
      if (handle == null) return
      clearTimeout(handle)
      delete this.pendingTimeouts[nodeId]
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

    // 置顶/取消置顶：本地就地替换 typeApp 对象引用以触发响应式更新
    setPinnedLocal(nodeId: string, pinned: boolean) {
      const node = this.nodes[nodeId]
      if (!node?.typeApp) return
      this.nodes[nodeId] = { ...node, typeApp: { ...node.typeApp, pinned } }
    },

    removeNode(id: string) {
      this.clearResolutionWatch(id)
      const from = this.parentKeyOf(id)
      delete this.nodes[id]
      for (const k of Object.keys(this.order)) this.order[k] = this.order[k].filter((x) => x !== id)
      delete this.order[id]
      // 删除后源文件夹若 ≤1 项 → 解散，杜绝单项文件夹残留
      if (from && from !== ROOT_KEY && this.nodes[from]?.type === HomeItemType.BOOKMARK_DIR && (this.order[from]?.length ?? 0) <= 1) {
        this.dissolveFolderLocal(from)
      }
    },

    // 删除一个节点及其全部子孙（后端 deleteByIds 对 BOOKMARK_DIR 会级联删除子项，本地需保持一致）
    removeSubtree(id: string) {
      const node = this.nodes[id]
      if (node?.type === HomeItemType.BOOKMARK_DIR) {
        for (const cid of [...(this.order[id] ?? [])]) this.removeSubtree(cid)
      }
      this.clearResolutionWatch(id)
      delete this.nodes[id]
      delete this.order[id]
      for (const k of Object.keys(this.order)) this.order[k] = this.order[k].filter((x) => x !== id)
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
      // 本地解散只改了本地状态，后端仍认为文件夹和其残留子项存在 —— 不同步的话，下次全量刷新
      // （缓存过期 / F5）会让已"消失"的文件夹从后端重新长出来，把排序搅乱。这里把解散动作异步
      // 补写回后端：先把残留子项移出文件夹，再删除已空的文件夹节点本身；不阻塞、不影响本地已完成的即时反馈。
      this.persistDissolve(folderId, children)
    },

    async persistDissolve(folderId: string, children: string[]) {
      try {
        const [remainingId] = children
        if (remainingId) await bookmarksMoveNode(remainingId, null)
        await bookmarksDel([folderId])
      } catch (error) {
        console.error('[bookmark] 文件夹解散状态同步到后端失败', { folderId, children, error })
      }
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

    // 兜底去重（历史踩坑#10）：跨卡片拖拽若两侧同步不彻底，同一 id 会同时残留在根列表和某个
    // 文件夹的 children 里——v-for :key 重复不会报错也不会合并渲染两行，而是让该 id 在多个
    // order 列表里各计一次，folder 卡片标题旁的 ({{ children.length }}) 就会比实际书签数多。
    // 只保留 id 第一次出现的位置，跨列表的重复项直接丢弃。
    dedupeLayout(): boolean {
      const seen = new Set<string>()
      let changed = false
      for (const key of Object.keys(this.order)) {
        const deduped = (this.order[key] ?? []).filter((id) => {
          if (seen.has(id)) return false
          seen.add(id)
          return true
        })
        if (deduped.length !== this.order[key]?.length) {
          changed = true
          this.order[key] = deduped
        }
      }
      if (changed) {
        console.error(
          '%c[重大事故]',
          'color:#fff;background:#dc2626;font-weight:bold;padding:1px 6px;border-radius:3px',
          '检测到同一 id 同时存在于多个列表，已去重（仅保留首次出现）',
        )
      }
      return changed
    },
  },

  // 显式指定 localStorage：裸 `persist: true` 在本项目未配置模块级 storage 时，
  // pinia-plugin-persistedstate 会回退到 cookies（单条 ~4KB 上限），书签树（含内嵌
  // base64 图标）写入必然静默失败/截断，导致缓存永远无法在 F5 后存活。与
  // preference.store 写法保持一致。
  // pendingTimeouts 是进程内定时器句柄，刷新后必然失效，排除在持久化范围外。
  // afterHydrate 在水合完成后立即跑一次 dedupeLayout：坏数据可能是很久以前的一次拖拽留下的，
  // 只在下次 setLayout() 才检查为时已晚——用户在这之前已经看到错误计数了。
  persist: {
    storage: piniaPluginPersistedstate.localStorage(),
    paths: ['nodes', 'order', 'lastFetchedAt'],
    afterHydrate: (ctx) => {
      const order = ctx.store.order as Record<string, string[]>
      const seen = new Set<string>()
      for (const key of Object.keys(order)) {
        order[key] = (order[key] ?? []).filter((id) => {
          if (seen.has(id)) return false
          seen.add(id)
          return true
        })
      }
    },
  },
})
