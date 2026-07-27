import type { BookmarkShow } from './bookmark'
import type { ShareStatus } from './enum'

export interface ShareCreateParams {
  bookmarkUserLinkIds: string[]
  note?: string
  expireTime?: string | null
}

export interface ShareListParams {
  currentPage?: number
  pageSize?: number
}

export interface ShareUpdateParams {
  id: string
  note?: string
  expireTime?: string | null
}

export interface ShareVO {
  id: string
  note?: string
  expireTime?: string | null
  status: ShareStatus
  bookmarkCount: number
  createTime: number
}

export interface ShareSharerVO {
  nickName: string
  avatarUrl?: string | null
}

export interface SharePublicVO {
  id: string
  note?: string
  expireTime?: string | null
  status: ShareStatus
  sharer: ShareSharerVO
  bookmarks: BookmarkShow[]
}
