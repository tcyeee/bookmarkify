import type { HomeItemType } from './enum'

// 书签节点
export interface UserLayoutNodeVO {
  id: string
  sort: number
  type: HomeItemType
  parentId?: string | null
  name?: string | null
  typeApp?: BookmarkShow
  typeFuc?: string
  children?: Array<UserLayoutNodeVO>
}

// 书签详情（后端 BookmarkShow）
export interface BookmarkShow {
  bookmarkId: string
  bookmarkUserLinkId: string
  title: string
  description: string
  urlFull: string
  urlBase: string
  iconBase64: string
  iconHdUrl: string
  isActivity: boolean
  createTime?: number
  paths?: Array<string>
}

export interface BookmarkDir {
  name?: string
  bookmarkList: Array<BookmarkShow>
}

export interface BookmarkUpdateParams {
  id: string
  iconActivity?: boolean
  iconHd?: boolean
}

export interface BookmarksAddOneParams {
  url: string
}

export interface BookmarkListParams {
  name?: string
  currentPage?: number
  pageSize?: number
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
