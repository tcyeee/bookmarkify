import { defineStore } from 'pinia'
import type { HomeItem } from '~/server/apis/bookmark/typing'
import { bookmarksShowAll } from "~/server/apis";

export const StoreBookmark = defineStore('bookmarks', {
  persist: true,
  state: () => (<{
    bookmarks: Array<HomeItem> | undefined,
  }>{
      bookmarks: undefined
    }),
  getters: {
  },
  actions: {
    async get(): Promise<Array<HomeItem>> {
      console.log(`[DEBUG]书签查询:${this.bookmarks?.length}`);
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
      return this.bookmarks;
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
