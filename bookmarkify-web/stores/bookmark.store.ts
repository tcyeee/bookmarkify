import { defineStore } from 'pinia'
import { HomeItemType, type UserLayoutNodeVO } from '@typing'
import { bookmarksShowAll } from '@api'

export const useBookmarkStore = defineStore('homeItems', {
  state: () => ({
    layoutNode: [] as Array<UserLayoutNodeVO>, // 用户桌面布局信息
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
      if (this.layoutNode === undefined) throw new Error('Bookmarks is undefined')
      return this.layoutNode
    },

    // 在书签列表中临时插入一个“加载中”的占位项
    addEmpty(item: UserLayoutNodeVO) {
      const placeholder: UserLayoutNodeVO = {
        children: [],
        ...item,
        type: HomeItemType.BOOKMARK_LOADING,
      }
      this.layoutNode?.push(placeholder)
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
    },
  },
  persist: { storage: piniaPluginPersistedstate.localStorage() },
})
