import type { BookmarkShow } from './bookmark'

export interface ShareCreateParams {
  bookmarkUserLinkIds: string[]
  note?: string
  expireTime?: string | null
}

export interface ShareVO {
  id: string
  note?: string
  expireTime?: string | null
  status: string
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
  status: string
  sharer: ShareSharerVO
  bookmarks: BookmarkShow[]
}
