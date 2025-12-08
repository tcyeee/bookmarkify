import type { BackgroundType, CurrentEnvironment, HomeItemType, UserFileType } from './enum'

export interface LoginParams {
  account: string
  password: string
}

export interface UserAuthParams {
  deviceUid: string
  token?: string
}

export interface BackgroundGradientEntity {
  colors: string[] // 渐变色数组，至少2个颜色
  direction?: number // 渐变方向角度，默认135
}

export interface UserSetting {
  backgroundSetting: BackgroundConfig
}

export interface BackgroundConfig {
  type: BackgroundType
  gradient?: BackgroundGradientEntity // 当type为GRADIENT时使用
  imagePath?: string // 当type为IMAGE时使用
}

export interface UserInfoEntity {
  uid: string
  token: string
  nickName: string
  phone?: string
  email?: string
  verified: boolean
  avatar: UserFile
}

export interface UserFile {
  id: string /* 文件ID */
  uid: string /* 文件所属用户ID */
  environment: CurrentEnvironment
  originName: string
  currentName: string
  type: UserFileType
  size: number /* 文件大小(单位:字节) */
  createTime: string /* 文件创建时间 */
  deleted: boolean
}

export interface UserInfoUpdate {
  nickName?: string
  phone?: string
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
  uid: string
  bookmarkId: string
  bookmarkUserLinkId: string

  isActivity: string
  urlFull: string
  title: string
  description: string

  iconActivity: boolean
  iconHd: boolean
  iconUrlFull: string
}

export interface BookmarkUpdateParams {
  id: string
  iconActivity?: boolean
  iconHd?: boolean
}

export interface bookmarksAddOneParams {
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
