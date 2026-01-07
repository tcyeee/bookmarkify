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
      const replace = (list?: Array<UserLayoutNodeVO>): boolean => {
        if (!list) return false
        const index = list.findIndex((it) => it.id === item.id)
        if (index >= 0) {
          const mergedChildren = item.children ?? list[index]?.children ?? []
          list.splice(index, 1, {
            ...item,
            children: mergedChildren,
          })
          return true
        }
        return list.some((child) => replace(child.children))
      }
      replace(this.layoutNode)
    },
  },
  persist: { storage: piniaPluginPersistedstate.localStorage() },
})
