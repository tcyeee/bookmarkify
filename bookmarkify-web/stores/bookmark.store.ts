import { defineStore } from 'pinia'
import { HomeItemType, type Bookmark, type HomeItem } from '@typing'
import { bookmarksShowAll } from '@api'

// 书签相关的 Pinia Store（负责桌面书签布局及其更新通知）
export const useBookmarkStore = defineStore('bookmarks', {
  state: () => ({
    // 用户桌面布局信息（书签列表）
    bookmarks: [] as Array<HomeItem>,
  }),

  actions: {
    // 对书签列表按 sort 字段排序，保持原数组不被修改
    sortData(res: Array<HomeItem>) {
      return !res || res.length === 0 ? [] : res.slice().sort((a, b) => a.sort - b.sort)
    },

    // 从后端拉取全部书签并更新本地缓存
    async update(): Promise<Array<HomeItem>> {
      const res = await bookmarksShowAll()
      this.bookmarks = this.sortData(res)
      console.log(`[DEBUG]书签更新:${this.bookmarks?.length}`)
      if (this.bookmarks === undefined) throw new Error('Bookmarks is undefined')

      return this.bookmarks
    },

    // 局部更新某一个书签的数据（通常由 WebSocket 推送触发）
    updateOne(item: Bookmark) {
      // 使用 splice 替换数组元素，确保产生新的响应式引用，通知到视图层
      const index = this.bookmarks?.findIndex(
        (it) => it.type === HomeItemType.BOOKMARK && it.typeApp.bookmarkId === item.bookmarkId
      )
      if (index !== undefined && index >= 0 && this.bookmarks) {
        const current = this.bookmarks[index]
        if (!current) return
        this.bookmarks.splice(index, 1, { ...current, typeApp: { ...item } })
      }
    },
  },

  // 通过 pinia-plugin-persistedstate 将书签持久化到 localStorage
  persist: { storage: piniaPluginPersistedstate.localStorage() },
})
