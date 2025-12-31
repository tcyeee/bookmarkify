import { defineStore } from 'pinia'
import { HomeItemType, type Bookmark, type HomeItem } from '@typing'
import { bookmarksShowAll } from '@api'

// 书签相关的 Pinia Store（负责桌面书签布局及其更新通知）
export const useBookmarkStore = defineStore('bookmarks', {
  state: () => ({
    // 用户桌面布局信息（书签列表）
    bookmarks: undefined as Array<HomeItem> | undefined,
    // 当书签更新后的回调函数集合（key 为回调函数名）
    actions: {} as Record<string, Function>,
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

      // 触发所有监听书签变更的回调
      this.trigger()
      return this.bookmarks
    },

    // 触发所有已注册的书签更新回调
    trigger() {
      Object.values(this.actions).forEach((action) => action())
    },

    // 注册一个在书签更新时需要执行的回调函数
    addAction(name: string, action: Function) {
      this.actions[name] = action
      console.log(`注册书签更新回调: ${name},当前回调数量: ${Object.keys(this.actions).length}`)
    },

    // 在书签列表中临时插入一个“加载中”的占位项
    addEmpty(item: HomeItem) {
      item.type = HomeItemType.LOADING
      this.bookmarks?.push(item)
      this.trigger()
    },

    // 局部更新某一个书签的数据（通常由 WebSocket 推送触发）
    updateOne(item: Bookmark) {
      this.bookmarks?.forEach((it) => {
        if (it.type === HomeItemType.BOOKMARK && it.typeApp.bookmarkId === item.bookmarkId) {
          it.typeApp = item
        }
      })
    },
  },

  // 通过 pinia-plugin-persistedstate 将书签持久化到 localStorage
  persist: { storage: piniaPluginPersistedstate.localStorage() },
})
