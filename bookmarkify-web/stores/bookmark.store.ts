import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import { HomeItemType, type Bookmark, type HomeItem } from '@api/typing'
import { bookmarksShowAll } from '@api'

export const useBookmarkStore = defineStore('bookmarks', () => {
  const bookmarks = ref<Array<HomeItem> | undefined>(undefined)
  const actions = reactive<Record<string, Function>>({})

  function sortData(res: Array<HomeItem>) {
    return !res || res.length === 0 ? [] : res.slice().sort((a, b) => a.sort - b.sort)
  }

  // async function get(): Promise<Array<HomeItem>> {
  //   if (bookmarks.value === undefined) return await update()
  //   return bookmarks.value
  // }

  async function update(): Promise<Array<HomeItem>> {
    const res = await bookmarksShowAll()
    bookmarks.value = sortData(res)
    console.log(`[DEBUG]书签更新:${bookmarks.value?.length}`)
    if (bookmarks.value === undefined) throw new Error('Bookmarks is undefined')

    trigger()
    return bookmarks.value
  }

  function trigger() {
    Object.values(actions).forEach(action => action())
  }

  function addAction(action: Function) {
    actions[action.name] = action
  }

  function addEmpty(item: HomeItem) {
    item.type = HomeItemType.LOADING
    bookmarks.value?.push(item)
    trigger()
  }

  function updateOne(item: Bookmark) {
    bookmarks.value?.forEach(it => {
      if (it.bookmarkId === item.bookmarkId) {
        it.type = HomeItemType.BOOKMARK
        it.typeApp = item
      }
    })
  }

  return {
    bookmarks,
    actions,
    trigger,
    addAction,
    addEmpty,
    updateOne,
    update
  }
}, { persist: true });