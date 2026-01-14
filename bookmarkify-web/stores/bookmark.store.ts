import { defineStore } from 'pinia'
import { HomeItemType, type BookmarkShow, type UserLayoutNodeVO } from '@typing'
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

      // 通过item.id, 在(layoutNode)中找到对应的布局节点,并更新
      const findAndUpdate = (nodes: Array<UserLayoutNodeVO>): boolean => {
        for (let i = 0; i < nodes.length; i++) {
          const currentNode = nodes[i]
          if (!currentNode) continue

          if (currentNode.id === item.id) {
            // 找到匹配的节点，更新它（保留原有的 children 结构）
            console.log(`[DEBUG]更新桌面节点:${item.id}`, item);
            // 使用 Vue 响应式的方式更新：先创建新数组，再替换整个数组
            // 这样可以确保 Vue 检测到变化
            const updatedNode = { ...item, children: currentNode.children ?? item.children ?? [] }
            // 使用 splice 确保响应式更新
            nodes.splice(i, 1, updatedNode)
            return true
          }
          // 递归查找子节点
          if (currentNode.children && currentNode.children.length > 0) {
            if (findAndUpdate(currentNode.children)) {
              return true
            }
          }
        }
        return false
      }

      if (this.layoutNode && this.layoutNode.length > 0) {
        findAndUpdate(this.layoutNode)
        // 强制触发响应式更新：创建一个新数组引用
        this.layoutNode = [...this.layoutNode]
      }
    },
  },
  persist: { storage: piniaPluginPersistedstate.localStorage() },
})
