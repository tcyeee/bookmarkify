import type { HomeItemType } from './enum'

export interface BookmarkUpdateParams {
  id: string
  iconActivity?: boolean
  iconHd?: boolean
}

export interface BookmarksAddOneParams {
  url: string
}

export interface BookmarkTag {
  id: string
  name: string
  uid: string
  description: string
  color: string
}

export interface BookmarkDetail {
  bookmark: Bookmark
  tags: Array<BookmarkTag>
}

export interface HomeItem {
  id: string
  uid: string
  sort: number
  type: HomeItemType // 书签类型,可用值:
  typeApp: Bookmark
  typeDir: BookmarkDir
  typeFuc: string // 方法枚举 USER_INFO BOOKMARK_MANAGE
  bookmarkId?: string // 用于新建书签时定位
}

export interface BookmarkDir {
  name: string
  bookmarkList: Array<Bookmark>
  bookmarkUserLinkIds: Array<string>
}

export interface BookmarkUpdatePrams {
  linkId: string
  title: string
  description: string
}

export interface BookmarkSortParams {
  id: string
  sort: number
}

export interface Bookmark {
  bookmarkId: string
  bookmarkUserLinkId: string
  title: string
  description: string
  urlFull: string
  urlBase: string
  iconBase64: string
  iconHdUrl: string
  isActivity: boolean
}
