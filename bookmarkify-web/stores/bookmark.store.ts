import { defineStore } from 'pinia'
import { HomeItemType, type Bookmark, type HomeItem } from '@typing'
import { bookmarksShowAll } from '@api'


export const useBookmarkStore = defineStore('bookmarks', {
  state: () => ({
    homeItems: [] as Array<HomeItem>,  // 用户桌面布局信息
  }),

  actions: {
    // 对书签列表按 sort 字段排序，保持原数组不被修改
    sortData(res: Array<HomeItem>) {
      return !res || res.length === 0 ? [] : res.slice().sort((a, b) => a.sort - b.sort)
    },

    // 从后端拉取全部桌面布局信息并更新本地缓存
    async update(): Promise<Array<HomeItem>> {
      const res = await bookmarksShowAll()
      this.homeItems = this.sortData(res)
      console.log(`[DEBUG]桌面布局全部信息更新:${this.homeItems?.length}`)
      if (this.homeItems === undefined) throw new Error('Bookmarks is undefined')
      return this.homeItems
    },

    // 在书签列表中临时插入一个“加载中”的占位项
    addEmpty(item: HomeItem) {
      item.type = HomeItemType.LOADING
      this.homeItems?.push(item)
    },

    // 局部更新某一个桌面布局Item（通常由 WebSocket 推送触发）
    updateOneBookmarkCell(item: HomeItem) {
      const index = this.homeItems?.findIndex((it) => it.id === item.id)
      if (index !== undefined && index >= 0 && this.homeItems) {
        this.homeItems.splice(index, 1, { ...item })
      }
    },
  },

  // 通过 pinia-plugin-persistedstate 将书签持久化到 localStorage
  persist: { storage: piniaPluginPersistedstate.localStorage() },
})
