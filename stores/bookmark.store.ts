import { defineStore } from 'pinia'
import { HomeItemType, type Bookmark, type HomeItem } from '~/server/apis/bookmark/typing'
import { bookmarksShowAll } from "~/server/apis";

export const StoreBookmark = defineStore('bookmarks', {
  state: () => ({
    bookmarks: undefined as Array<HomeItem> | undefined,
    actions: {} as Record<string, Function>
  }),
  actions: {
    async get(): Promise<Array<HomeItem>> {
      return this.bookmarks == undefined ? await this.update() : Promise.resolve(this.bookmarks)
    },

    /**
     * 查询桌面排布数据
     * @returns 排序后的桌面排布数据
     */
    async update(): Promise<Array<HomeItem>> {
      await bookmarksShowAll().then((res) => this.bookmarks = sortData(res))
      console.log(`[DEBUG]书签更新:${this.bookmarks?.length}`);
      if (this.bookmarks == undefined) throw new Error

      this.trigger()
      return this.bookmarks;
    },

    trigger() {
      Object.values(this.actions).forEach(action => { action() });
    },

    addAction(action: Function) {
      this.actions[action.name] = action
    },

    addEmpty(item: HomeItem) {
      item.type = HomeItemType.LOADING
      this.bookmarks?.push(item)
      this.trigger()
    },

    updateOne(item: Bookmark) {
      this.bookmarks?.forEach(it => {
        if (it.bookmarkId === item.bookmarkId) {
          it.type = HomeItemType.BOOKMARK
          it.typeApp = item
        }
      })
    }
  }
})


/**
 * 数据排序
 * 
 * @param res 原始桌面数据
 * @returns 排序后的桌面数据
 */

function sortData(res: Array<HomeItem>) {
  return res == null || res.length == 0 ? [] : res.slice().sort((a, b) => a.sort - b.sort);
}
