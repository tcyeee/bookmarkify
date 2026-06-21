import { defineStore } from 'pinia'
import { HomeItemType, type UserLayoutNodeVO } from '@typing'
import { bookmarksShowAll } from '@api'

export const useBookmarkStore = defineStore('homeItems', {
  state: () => ({
    layoutNode: [] as Array<UserLayoutNodeVO>, // 用户桌面布局信息
    // 单元格内容被「就地替换」时自增（如 LOADING→BOOKMARK）。
    // Vuuri 仅按 id 做增删 diff，无法感知同 id 节点的内容变化，
    // 页面 watch 此值后 bump gridKey 强制 Vuuri 重新同步。
    cellRevision: 0,
  }),

  actions: {
    // 对书签列表按 sort 字段排序，保持原数组不被修改，并递归处理子节点
    sortData(res?: Array<UserLayoutNodeVO>): Array<UserLayoutNodeVO> {
      if (!res || res.length === 0) return []
      return res
        .map((item) => ({
          ...item,
          sort: item.sort ?? Number.MAX_SAFE_INTEGER,
          children: item.children ? this.sortData(item.children) : [],
        }))
        .sort((a, b) => a.sort - b.sort)
    },

    // 从后端拉取全部桌面布局信息并更新本地缓存（后端返回根节点，前端取 children 展示）
    async update(): Promise<Array<UserLayoutNodeVO>> {
      const res = await bookmarksShowAll()
      const children = res?.children ?? []
      this.layoutNode = this.sortData(children)
      console.log(`[DEBUG]桌面布局全部信息更新:${this.layoutNode?.length}`)
      return this.layoutNode
    },

    /**
     * 去重：同一 id 不应同时出现在根与某文件夹 children（跨网格移动中断时可能产生）。
     * 重复 key 会让 Vuuri 渲染异常甚至死循环（持久化后刷新即卡死），故保留首次出现、移除后续重复。
     * 后端 update() 会再以权威数据校正，本方法只为保证可渲染、防卡死。
     */
    dedupeLayout() {
      const seen = new Set<string>()
      const walk = (nodes: Array<UserLayoutNodeVO>): Array<UserLayoutNodeVO> => {
        const out: Array<UserLayoutNodeVO> = []
        for (const n of nodes) {
          if (!n?.id || seen.has(n.id)) continue
          seen.add(n.id)
          out.push(n.children && n.children.length > 0 ? { ...n, children: walk(n.children) } : n)
        }
        return out
      }
      this.layoutNode = walk(this.layoutNode ?? [])
    },

    // 在书签列表中临时插入一个“加载中”的占位项
    addEmpty(item: UserLayoutNodeVO) {
      const placeholder: UserLayoutNodeVO = {
        ...item,
        children: item.children ?? [],
        type: HomeItemType.BOOKMARK_LOADING,
      }
      this.layoutNode?.push(placeholder)
    },

    // 递归删除一个节点(可能位于根或文件夹内),并保持响应式引用更新
    deleteOneBookmarkCell(id: string) {
      const removeRecursive = (nodes: Array<UserLayoutNodeVO>): Array<UserLayoutNodeVO> =>
        nodes
          .filter((n) => n.id !== id)
          .map((n) =>
            n.children && n.children.length > 0 ? { ...n, children: removeRecursive(n.children) } : n,
          )
      this.layoutNode = removeRecursive(this.layoutNode ?? [])
    },

    // 局部更新某一个桌面布局Item（通常由 WebSocket 推送触发），包含子节点
    updateOneBookmarkCell(item: UserLayoutNodeVO) {
      // 仅仅只更新书签类型
      if (item.type !== HomeItemType.BOOKMARK || item.typeApp == null) return

      if (!this.layoutNode || this.layoutNode.length === 0) return

      // 递归更新节点，创建全新的对象引用以确保 Vue 响应式系统能够检测到变化
      const updateNodeRecursive = (nodes: Array<UserLayoutNodeVO>): Array<UserLayoutNodeVO> => {
        return nodes.map((currentNode) => {
          if (currentNode.id === item.id) {
            // 找到匹配的节点，创建全新的对象引用（包括 typeApp 等嵌套对象）
            console.log(`[DEBUG]更新桌面节点:${item.id}`, item)
            return {
              ...item,
              // 确保 typeApp 也是新引用（如果存在）
              typeApp: item.typeApp ? { ...item.typeApp } : item.typeApp,
              // 保留原有的 children 结构，如果存在则递归更新
              children: currentNode.children && currentNode.children.length > 0
                ? updateNodeRecursive(currentNode.children)
                : item.children ?? []
            }
          }
          // 递归处理子节点
          if (currentNode.children && currentNode.children.length > 0) {
            return {
              ...currentNode,
              children: updateNodeRecursive(currentNode.children)
            }
          }
          return currentNode
        })
      }

      // 创建全新的数组引用，确保 Vue 响应式系统能够检测到变化
      this.layoutNode = updateNodeRecursive(this.layoutNode)
      // 通知页面：节点为「就地内容替换」，需强制 Vuuri 重新同步
      this.cellRevision++
    },
  },
  persist: {
    storage: piniaPluginPersistedstate.localStorage(),
    // 水合后立即去重，避免历史坏数据（重复 id）导致刷新卡死
    afterHydrate: (ctx) => ctx.store.dedupeLayout(),
  },
})
