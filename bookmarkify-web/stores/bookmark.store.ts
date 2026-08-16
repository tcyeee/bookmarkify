import { defineStore } from 'pinia'
import { HomeItemType, ROOT_KEY, type UserLayoutNodeVO } from '@typing'
import { bookmarksShowAll, bookmarksMoveNode, bookmarksDel } from '@api'

// 兜底重试的退避上限：单次最多等 5 分钟
const MAX_RESOLUTION_DELAY_MS = 5 * 60 * 1000
// 兜底重试次数。从 30s 起逐次翻倍并封顶 5 分钟，8 次累计约 35 分钟，刚好跨过后端
// drainStuckLoading 的 30 分钟陈旧阈值——那之后服务端会重新解析并重新推送，再轮询也无意义
const MAX_RESOLUTION_ATTEMPTS = 8
// 同时挂载的兜底监听上限，见 armPendingWatches
const MAX_ARMED_WATCHES = 20
// 批量导入完成提示的超时兜底：留 15 分钟余量越过后端 drainStuckLoading 的 30 分钟陈旧阈值，
// 避免个别节点因我方抓取服务故障（E307）长期停在 LOADING 导致这批导入永远等不到"全部完成"
const IMPORT_BATCH_TIMEOUT_MS = 45 * 60 * 1000

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

// 一次批量导入任务的追踪记录，用于导入完成/超时后弹一次 toast，见 checkImportBatches
interface ImportBatch {
  id: string // crypto.randomUUID()，仅用于日志区分，不与后端有任何关联
  nodeIds: string[] // 注册时快照的 BOOKMARK_LOADING 节点 id 列表（不含已秒解析的文件夹）
  total: number // nodeIds.length，避免重复计算
  startedAt: number // Date.now()，用于 IMPORT_BATCH_TIMEOUT_MS 超时兜底判断
}

export const useBookmarkStore = defineStore('homeItems', {
  state: () => ({
    nodes: {} as Record<string, UserLayoutNodeVO>,
    order: { [ROOT_KEY]: [] } as Record<string, string[]>,
    lastFetchedAt: 0,
    // LOADING 节点的兜底定时器句柄（进程内瞬时状态，不落盘，见下方 persist.pick）
    pendingTimeouts: {} as Record<string, ReturnType<typeof setTimeout>>,
    // 各 LOADING 节点已用掉的兜底重试次数（同上，进程内瞬时状态），见 watchForResolution
    resolutionAttempts: {} as Record<string, number>,
    // 在途的 update() 请求（同上，进程内瞬时状态）。多个兜底定时器同时到点、
    // 或重连补拉与兜底重试撞在一起时，共用同一次请求而不是各发一次
    inflightUpdate: null as Promise<void> | null,
    // 进行中的批量导入任务，见 addImportLoadingBatch / checkImportBatches
    importBatches: [] as ImportBatch[],
  }),

  getters: {
    // 文件夹节点即时填充 children，供 pages/index.vue 的文件夹卡片渲染子项使用
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
    // 全部被置顶的书签节点（不区分所在文件夹），按用户在置顶区里排好的顺序。
    //
    // 用 typeApp.pinnedSort 而不是节点自身的 sort：后者是「本节点在它所属的那一层里排第几」，
    // 两条来自不同文件夹的书签拿各自的 sort 相比得到的顺序是巧合；而且拖动置顶区若去改 sort，
    // 会连带把书签在它自己文件夹里的位置也挪了。pinnedSort 是后端 bookmark 行上独立的一列。
    // 历史数据全是 0，此时退回按节点 sort 排（与改动前的表现一致），用户拖一次就写实了。
    pinnedNodes(state): Array<UserLayoutNodeVO> {
      return Object.values(state.nodes)
        .filter((n) => n.type === HomeItemType.BOOKMARK && n.typeApp?.pinned)
        .sort((a, b) => (a.typeApp!.pinnedSort ?? 0) - (b.typeApp!.pinnedSort ?? 0) || (a.sort ?? 0) - (b.sort ?? 0))
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
    // 全量拉取桌面布局。并发调用共用同一次请求：兜底定时器、WebSocket 重连补拉、页面加载
    // 三条路径都会调它，且完全可能同时到点——各发一次除了浪费没有任何收益。
    async update(): Promise<void> {
      if (this.inflightUpdate) return this.inflightUpdate
      this.inflightUpdate = (async () => {
        const res = await bookmarksShowAll()
        this.setLayout(res)
        this.lastFetchedAt = Date.now()
        console.log(`[DEBUG]桌面布局更新: 根 ${this.order[ROOT_KEY]?.length ?? 0} 项`)
      })().finally(() => {
        this.inflightUpdate = null
      })
      return this.inflightUpdate
    },

    // update() 的即发即忘版本：失败只记日志。
    // 兜底定时器、WebSocket 重连补拉这些路径没有调用方去 await，而 http.ts 的所有失败路径都是
    // Promise.reject —— 直接调 update() 会在控制台留下 unhandled rejection，且掩盖真正的原因。
    // 拉取失败本身不需要惊动用户：下一次兜底重试还会再来一遍。
    refresh(reason: string) {
      this.update().catch((error) => {
        console.warn(`[bookmark] 布局补拉失败(${reason})，等待下一次重试`, error)
      })
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
      // 服务端这份布局里已经没有的节点，其兜底定时器也该跟着走，否则它会一直空转到超时
      for (const id of Object.keys(this.pendingTimeouts)) {
        if (!nodes[id]) this.clearResolutionWatch(id)
      }
      this.nodes = nodes
      this.order = order
      // 防御：后端不应返回 ≤1 项文件夹，若出现即重大事故 → 报警 + 强制自愈
      this.enforceFolderInvariant()
      // 拉回来的布局里若仍有 LOADING 节点，就地把兜底监听补上——不能指望每个调用方记得挂
      this.armPendingWatches()
      // 整棵树被替换，覆盖兜底轮询补拉、WS 重连补拉两条路径——标签页关闭重开后正是靠这条
      // 拿到"其实早就解析完了"的新鲜数据
      this.checkImportBatches()
    },

    // 插入加载占位项到根
    addLoading(node: UserLayoutNodeVO) {
      console.log(`[bookmark] 插入 LOADING 占位节点: nodeId=${node.id}, name=${node.name ?? ''}, 等待 WebSocket 推送解析结果`)
      this.nodes[node.id] = { ...node, type: HomeItemType.BOOKMARK_LOADING, parentId: null, children: undefined }
      this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), node.id]
    },

    // 兜底：解析结果靠 WebSocket 推送，是尽力而为的——连接断开/消息丢失时后端不会重试推送。
    // 超时仍未解除 LOADING 就主动整体重新拉取桌面布局对账，避免节点永远转圈。
    //
    // **必须重试多次**：首发 30s 几乎必然扑空——后端单条解析的尾部耗时是 scrapper 读超时 60s
    // 加上 appName 推断 10s，30s 时那条书签八成还在抓。旧实现只发一次、发完就把定时器删了，
    // 于是"最该起作用的那一次"恰好什么都没捞到，之后就彻底没有兜底了。
    // 现在按 delayMs 逐次翻倍（上限 [MAX_RESOLUTION_DELAY_MS]）重试到 [MAX_RESOLUTION_ATTEMPTS] 次，
    // 覆盖范围要跨过后端 drainStuckLoading 的 30 分钟陈旧阈值——那是最后一道服务端补推。
    watchForResolution(nodeId: string, delayMs = 30000, attempt = 0) {
      const wait = Math.min(delayMs * 2 ** attempt, MAX_RESOLUTION_DELAY_MS)
      console.log(`[bookmark] 开始兜底监听: nodeId=${nodeId}, 第 ${attempt + 1} 次, waitMs=${wait}`)
      this.clearResolutionWatch(nodeId)
      // 记下已用掉的次数：定时器句柄在触发那一刻就删了，光看句柄分不出"还没挂"和"已经挂满了"，
      // armPendingWatches 会把后者当成前者重新从第 1 次挂起——那样退避永远清零，等于无限轮询
      this.resolutionAttempts[nodeId] = attempt
      this.pendingTimeouts[nodeId] = setTimeout(() => {
        delete this.pendingTimeouts[nodeId]
        if (this.nodes[nodeId]?.type !== HomeItemType.BOOKMARK_LOADING) return
        // 先续上下一次，再拉取：update() → setLayout() → armPendingWatches() 会看到已有句柄
        // 而跳过这个节点，否则同一个节点会被挂上两个定时器
        if (attempt + 1 < MAX_RESOLUTION_ATTEMPTS) {
          this.watchForResolution(nodeId, delayMs, attempt + 1)
        } else {
          console.warn(`[bookmark] 节点 ${nodeId} 兜底重试已达上限，停止轮询（仍可由 WebSocket 推送解除）`)
        }
        console.warn(`[bookmark] 节点 ${nodeId} 等待 ${wait}ms 仍未收到解析结果，主动重新拉取桌面布局`)
        this.refresh(`节点 ${nodeId} 解析超时`)
      }, wait)
    },

    // 为当前所有还在 LOADING 的节点补挂兜底监听（已有监听的跳过）。
    //
    // 存在的意义是"不依赖调用方记得挂"：此前只有 AddOneDialog 与 BookmarkManage 两个入口手动挂，
    // 于是刷新页面之后——localStorage 恢复出来的 LOADING 节点、以及 plugins/auth.ts 在缓存新鲜时
    // 直接跳过拉取的那条路径——桌面上的转圈节点根本没有任何人看着，只能干等 WebSocket。
    armPendingWatches() {
      const pending = Object.values(this.nodes).filter(
        (n) =>
          n.type === HomeItemType.BOOKMARK_LOADING &&
          this.pendingTimeouts[n.id] == null &&
          (this.resolutionAttempts[n.id] ?? -1) < MAX_RESOLUTION_ATTEMPTS - 1,
      )
      if (!pending.length) return
      // update() 是整棵树一起拉的，挂几个就足以把所有 LOADING 一起对上账。批量导入会一次留下
      // 上千个 LOADING 节点，逐个挂定时器纯属浪费（它们本来也都指向同一次请求）
      const armed = pending.slice(0, MAX_ARMED_WATCHES)
      console.log(`[bookmark] 补挂兜底监听: ${armed.length}/${pending.length} 个 LOADING 节点`)
      for (const node of armed) this.watchForResolution(node.id)
    },

    // 收到 WS 更新 / 节点被手动删除时调用，取消其兜底定时器并清空重试计数。
    // 计数必须一并清掉，且不能因为"没有定时器"就提前返回——重试用尽的节点正是没有定时器、
    // 却留着计数的那一类，漏清的话这个 id 会一直躺在 resolutionAttempts 里。
    clearResolutionWatch(nodeId: string) {
      delete this.resolutionAttempts[nodeId]
      const handle = this.pendingTimeouts[nodeId]
      if (handle == null) return
      console.log(`[bookmark] 清除节点 ${nodeId} 的兜底监听定时器`)
      clearTimeout(handle)
      delete this.pendingTimeouts[nodeId]
    },

    isNodeStillLoading(id: string): boolean {
      const n = this.nodes[id]
      return n != null && n.type === HomeItemType.BOOKMARK_LOADING
    },

    // 批量导入完成/超时兜底检查。不订阅任何具体 WS 消息类型，而是直接读 this.nodes——本 store
    // 是节点状态的唯一真相来源，在 nodes 可能变化的三处入口（replaceContent / replaceFolder /
    // setLayout）末尾各调一次即可覆盖全部解析路径，外加 plugins/auth.ts 水合后调一次覆盖"标签页
    // 全程关闭、直到超时也没等到下一次拉取"的场景。旧版 importProgress.store.ts 正是因为只订阅了
    // HOME_ITEM_UPDATE 一种消息，节点经 HOME_DIR_UPDATE / HOME_LAYOUT_REFRESH 解析时计数器永远
    // 不递减，才会永久卡在未完成状态。
    // 仅客户端跑：该动作会触发 toast，SSR 阶段跑既无意义也不安全。
    checkImportBatches() {
      if (!import.meta.client || !this.importBatches.length) return
      const now = Date.now()
      const remaining: ImportBatch[] = []
      for (const batch of this.importBatches) {
        const stillLoading = batch.nodeIds.filter((id) => this.isNodeStillLoading(id)).length
        if (stillLoading === 0) {
          useToastStore().success(`书签导入完成，共导入 ${batch.total} 个书签！`)
        } else if (now - batch.startedAt > IMPORT_BATCH_TIMEOUT_MS) {
          // 超时仍未全部解除 LOADING：多半是个别书签撞上我方抓取服务故障（E307）被设计成停在
          // LOADING 不收口，不能让这批导入的完成提示永远等不到——按已完成的部分先提示
          useToastStore().info(`导入完成 ${batch.total - stillLoading}/${batch.total}，其余仍在后台处理，稍后会自动更新`)
        } else {
          remaining.push(batch)
        }
      }
      this.importBatches = remaining
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
      // 导入的 LOADING 占位同样需要兜底：后端对导入路径不发解析事件，全靠 drainStuckLoading
      // 分批捞，结果是零散推回来的——推丢一条就是一个永远转圈的格子
      this.armPendingWatches()
      // 登记这批导入，供 checkImportBatches 判断"是否全部解析完成"并弹出完成提示。
      // 过滤掉文件夹：文件夹是秒解析的，不该计入需要等待的总数
      const loadingIds = nodes.filter((n) => n.type === HomeItemType.BOOKMARK_LOADING).map((n) => n.id)
      if (loadingIds.length > 0) {
        this.importBatches.push({ id: crypto.randomUUID(), nodeIds: loadingIds, total: loadingIds.length, startedAt: Date.now() })
      }
    },

    // 新增已就绪书签到根（AddOneDialog 关联/添加成功且已带 typeApp）
    addNode(node: UserLayoutNodeVO) {
      this.nodes[node.id] = { ...node, parentId: null, children: undefined }
      this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), node.id]
    },

    // WebSocket 就地内容替换（LOADING→BOOKMARK）；仅改内容，保留归属，靠响应式重渲染
    replaceContent(node: UserLayoutNodeVO) {
      if (node.type !== HomeItemType.BOOKMARK || node.typeApp == null) {
        console.warn(`[bookmark] 收到的 WS 节点无法应用，已忽略: nodeId=${node.id}, type=${node.type}, hasTypeApp=${node.typeApp != null}`)
        return
      }
      const cur = this.nodes[node.id]
      if (!cur) {
        console.warn(`[bookmark] 收到 WS 更新但本地不存在该节点，已忽略: nodeId=${node.id}`)
        return
      }
      console.log(
        `[bookmark] 应用 WS 内容替换: nodeId=${node.id}, title=${node.typeApp.title || node.typeApp.urlBase}, isActivity=${node.typeApp.isActivity}`,
      )
      this.nodes[node.id] = { ...node, parentId: cur.parentId, children: undefined }
      this.checkImportBatches()
    },

    // WebSocket 文件夹结构同步（HOME_DIR_UPDATE）：用服务端下发的子节点列表整体替换该文件夹的
    // 内容与顺序。此前后端把文件夹更新塞在 HOME_ITEM_UPDATE 里，被 replaceContent 当作非法形状
    // 直接丢弃，导致多标签页/多设备之间的结构变动从来没有真正同步过。
    replaceFolder(node: UserLayoutNodeVO) {
      if (node.type !== HomeItemType.BOOKMARK_DIR) {
        console.warn(`[bookmark] 收到的 WS 文件夹节点类型不符，已忽略: nodeId=${node.id}, type=${node.type}`)
        return
      }
      const children = node.children ?? []
      // 覆盖前先记下本地这份子列表，用来算出「谁被移出去了」（见下方兜底）
      const prevChildIds = this.order[node.id] ?? []
      const cur = this.nodes[node.id]
      this.nodes[node.id] = { ...node, parentId: cur?.parentId ?? node.parentId ?? null, children: undefined }
      for (const child of children) {
        if (!child?.id) continue
        this.nodes[child.id] = { ...child, parentId: node.id, children: undefined }
      }
      const childIds = children.map((c) => c.id).filter(Boolean)
      this.order[node.id] = childIds
      // 子节点可能是刚从根目录（或别的文件夹）移进来的，得把它们从原来的列表里摘掉，
      // 否则同一个 id 会同时挂在两个 order 列表下，文件夹标题旁的计数就会多算一份
      const moved = new Set(childIds)
      for (const key of Object.keys(this.order)) {
        if (key === node.id) continue
        this.order[key] = (this.order[key] ?? []).filter((id) => !moved.has(id))
      }
      // 反向的那一半：本地记着、服务端这份列表里却没有的子节点，说明它被移出了这个文件夹。
      // 上面的整体替换已经把它从这里抹掉，而它并不在任何别的 order 列表里——节点还躺在 nodes 里，
      // 却没有任何一处会渲染它，在用户眼里就是凭空消失。先收到根目录兜底；若它其实是移进了
      // 另一个文件夹，紧随其后的那条 HOME_DIR_UPDATE 会再把它从根上摘走。
      const departed = prevChildIds.filter((id) => !moved.has(id) && this.nodes[id])
      if (departed.length) {
        const rootIds = this.order[ROOT_KEY] ?? []
        const orphans = departed.filter((id) => !rootIds.includes(id))
        if (orphans.length) {
          console.log(`[bookmark] 文件夹同步后有子节点无归属，暂收到根目录: folderId=${node.id}, ids=${orphans.join(',')}`)
          this.order[ROOT_KEY] = [...rootIds, ...orphans]
          for (const id of orphans) {
            const orphan = this.nodes[id]
            if (orphan) this.nodes[id] = { ...orphan, parentId: null }
          }
        }
      }
      // 文件夹自身若还没出现在任何列表里（本地没做过乐观更新，比如另一个标签页发起的操作），挂到根末尾
      if (!this.parentKeyOf(node.id)) {
        this.order[ROOT_KEY] = [...(this.order[ROOT_KEY] ?? []), node.id]
      }
      console.log(`[bookmark] 应用 WS 文件夹同步: folderId=${node.id}, name=${node.name ?? ''}, children=${childIds.length}`)
      this.checkImportBatches()
    },

    // 置顶/取消置顶：本地就地替换 typeApp 对象引用以触发响应式更新。
    // 新置顶的排到置顶区末尾，与后端 setPinned 的分配规则保持一致——不同步的话，这一格会先
    // 出现在最前面（pinnedSort 还是旧值或 0），下次刷新又跳到末尾。
    setPinnedLocal(nodeId: string, pinned: boolean) {
      const node = this.nodes[nodeId]
      if (!node?.typeApp) return
      const pinnedSort = pinned ? Math.max(-1, ...this.pinnedNodes.map((n) => n.typeApp!.pinnedSort ?? 0)) + 1 : node.typeApp.pinnedSort
      this.nodes[nodeId] = { ...node, typeApp: { ...node.typeApp, pinned, pinnedSort } }
    },

    // 置顶区重排：按传入的节点顺序重写各自的 pinnedSort。
    // 与 reorderLocal（改 order 顺序表）走的是完全不同的一条路——置顶区不是 order 里的一层，
    // 它是从各层里筛出来的视图，顺序只能记在书签自己身上。
    reorderPinnedLocal(nodeIds: Array<string>) {
      nodeIds.forEach((id, index) => {
        const node = this.nodes[id]
        if (!node?.typeApp) return
        this.nodes[id] = { ...node, typeApp: { ...node.typeApp, pinnedSort: index } }
      })
      if (import.meta.client) useNuxtApp().$track('pinned-reorder')
    },

    // 重命名文件夹的本地同步（后端确认成功后调用）。
    //
    // 收进 store 是因为原先两个组件各自写 `nodes[id] = { ...nodes[id], name }`：节点不存在时
    // 展开 undefined 得到的是 `{ name }` —— 一个没有 id、没有 type 的幽灵节点被塞回 nodes，
    // 渲染层拿它什么都做不了，却又不会报错。布局的写入本来就该由 store 统一收口。
    renameFolderLocal(folderId: string, name: string) {
      const node = this.nodes[folderId]
      if (!node) {
        console.warn(`[bookmark] 重命名的文件夹在本地不存在，跳过: folderId=${folderId}`)
        return
      }
      this.nodes[folderId] = { ...node, name }
    },

    removeNode(id: string) {
      this.clearResolutionWatch(id)
      const from = this.parentKeyOf(id)
      delete this.nodes[id]
      for (const k of Object.keys(this.order)) this.order[k] = (this.order[k] ?? []).filter((x) => x !== id)
      delete this.order[id]
      if (import.meta.client) useNuxtApp().$track('bookmark-delete')
      // 删除后源文件夹若 ≤1 项 → 解散，杜绝单项文件夹残留
      if (from && from !== ROOT_KEY && this.nodes[from]?.type === HomeItemType.BOOKMARK_DIR && (this.order[from]?.length ?? 0) <= 1) {
        this.dissolveFolderLocal(from)
      }
    },

    // 删除一个节点及其全部子孙（后端 deleteByIds 对 BOOKMARK_DIR 会级联删除子项，本地需保持一致）
    // isRoot 仅用于埋点去重：递归到子孙时传 false，避免一次文件夹删除按子项数量重复上报
    removeSubtree(id: string, isRoot = true) {
      const node = this.nodes[id]
      if (node?.type === HomeItemType.BOOKMARK_DIR) {
        for (const cid of [...(this.order[id] ?? [])]) this.removeSubtree(cid, false)
      }
      this.clearResolutionWatch(id)
      delete this.nodes[id]
      delete this.order[id]
      for (const k of Object.keys(this.order)) this.order[k] = (this.order[k] ?? []).filter((x) => x !== id)
      if (isRoot && import.meta.client) useNuxtApp().$track('folder-delete')
    },

    reorderLocal(parentKey: string, ids: Array<string>) {
      this.order[parentKey] = [...ids]
      if (import.meta.client) useNuxtApp().$track('bookmark-reorder')
    },

    moveLocal(id: string, toParentKey: string, index: number) {
      const from = this.parentKeyOf(id)
      if (from) this.order[from] = (this.order[from] ?? []).filter((x) => x !== id)
      const next = [...(this.order[toParentKey] ?? [])]
      next.splice(Math.max(0, Math.min(index, next.length)), 0, id)
      this.order[toParentKey] = next
      if (this.nodes[id]) this.nodes[id] = { ...this.nodes[id], parentId: toParentKey === ROOT_KEY ? null : toParentKey }
      if (import.meta.client) useNuxtApp().$track('bookmark-move')
      // 移出后源文件夹若 ≤1 项 → 正常解散（预期行为，不报警）。这是「绝不出现单项文件夹」的主路径。
      if (from && from !== ROOT_KEY && this.nodes[from]?.type === HomeItemType.BOOKMARK_DIR && (this.order[from]?.length ?? 0) <= 1) {
        this.dissolveFolderLocal(from)
      }
    },

    // 本地建夹：folderNode 为后端返回的真实文件夹节点；从根移除两子、文件夹落在 index、子顺序 [target, dragged]
    createFolderLocal(folderNode: UserLayoutNodeVO, draggedId: string, targetId: string, index: number) {
      this.nodes[folderNode.id] = { ...folderNode, parentId: null, children: undefined }
      // 两个子节点必须逐个确认存在再改写：本地没有这个 id 时展开 undefined 会得到一个既没有 id
      // 也没有 type 的对象，被当作节点塞进 nodes——渲染层拿它无从下手，也不会报任何错。
      // 同理，只有真实存在的子节点才进 order，否则文件夹的计数会比实际内容多。
      const children = [targetId, draggedId].filter((id) => {
        const child = this.nodes[id]
        if (!child) {
          console.warn(`[bookmark] 建夹时子节点在本地不存在，已跳过: nodeId=${id}, folderId=${folderNode.id}`)
          return false
        }
        this.nodes[id] = { ...child, parentId: folderNode.id, children: undefined }
        return true
      })
      const root = (this.order[ROOT_KEY] ?? []).filter((x) => x !== draggedId && x !== targetId)
      root.splice(Math.max(0, Math.min(index, root.length)), 0, folderNode.id)
      this.order[ROOT_KEY] = root
      this.order[folderNode.id] = children
      // 子节点没能凑够两个时，这个夹子本身就是不该存在的——交给不变量检查报警并自愈解散，
      // 而不是在桌面上留一个空文件夹
      this.enforceFolderInvariant()
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
  // pendingTimeouts / inflightUpdate 是进程内瞬时状态，刷新后必然失效，排除在持久化范围外。
  //
  // ⚠️ 字段名是 `pick`，不是 `paths`。`paths` 是 pinia-plugin-persistedstate v3 的写法，v4
  // 把它改名成了 `pick`（本项目装的是 4.7.1）—— 而**多余的键是被静默忽略的**，于是这个白名单
  // 一直没有生效，整个 state 都在被写进 localStorage。后果不只是多存了几个定时器句柄：Promise
  // 之类的值 JSON 序列化后是 `{}`，水合回来是个真值，`update()` 里那道「已有在途请求就复用」的
  // 判断会被它永久命中，桌面布局从此再也不会重新拉取。改错这个键不会有任何报错，只会让白名单
  // 悄悄失效，所以升级这个插件时要回头看一眼这里。
  //
  // afterHydrate 在水合完成后立即跑一次 dedupeLayout：坏数据可能是很久以前的一次拖拽留下的，
  // 只在下次 setLayout() 才检查为时已晚——用户在这之前已经看到错误计数了。
  persist: {
    storage: piniaPluginPersistedstate.localStorage(),
    pick: ['nodes', 'order', 'lastFetchedAt', 'importBatches'],
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
      // 注：缓存里恢复出来的 LOADING 节点需要补挂兜底监听，但那件事放在 plugins/auth.ts 里做——
      // 在这个选项对象里调 store 的 action 会让「选项 → store 类型 → 选项」成环，加重本文件已有的
      // Pinia 推断问题；而且 auth 插件那条「缓存新鲜就跳过拉取」的分支本来就是问题现场。
    },
  },
})
