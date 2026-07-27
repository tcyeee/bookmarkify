import type { BookmarkLinkType, FunctionType, HomeItemType } from './enum'

// 根层在归一化 order 中的固定键
export const ROOT_KEY = '__root__'

// 书签节点
export interface UserLayoutNodeVO {
  id: string
  sort: number
  type: HomeItemType
  parentId?: string | null
  name?: string | null
  typeApp?: BookmarkShow
  typeFuc?: BookmarkFunctionVO
  children?: Array<UserLayoutNodeVO>
}

export interface BookmarkFunctionVO {
  id: string,
  layoutNodeId: string,
  type: FunctionType,
}

// 书签图标信息（后端 website_logo 表，嵌套在 BookmarkShow.logo）
export interface BookmarkLogo {
  iconBase64?: string
  iconHdUrl?: string
  iconPadding?: number
  iconBgColor?: string
}

// 书签详情（后端 BookmarkShow）
export interface BookmarkShow {
  bookmarkId: string
  bookmarkUserLinkId: string
  title: string
  description: string
  urlFull: string
  urlBase: string
  pinned: boolean
  // 书签链接类型：本地/IP 类型不会被后端抓取，前端仅展示统一的 mdi 圆圈图标
  linkType: BookmarkLinkType
  // 图标相关字段统一收拢到 logo（后端 website_logo 表）
  logo: BookmarkLogo
  isActivity: boolean
  createTime?: number
  paths?: Array<string>
  // 用户桌面排布节点ID：批量删除(bookmarksDel)/创建集合(bookmarksCreateDir)等基于节点树的操作使用此ID，而非 bookmarkUserLinkId
  layoutNodeId?: string | null
  folderId?: string | null
  folderName?: string | null
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
  duplicatesOnly?: boolean
  invalidOnly?: boolean
}

export interface BookmarkPage<T = BookmarkShow> {
  records: Array<T>
  total: number
  current: number
  size: number
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

export interface BookmarkImportItemVO {
  title: string
  url: string
  folder?: string | null
  isDuplicate: boolean
}

export interface BookmarkImportPreviewVO {
  total: number
  duplicateCount: number
  items: BookmarkImportItemVO[]
}
