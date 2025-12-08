import type { BackgroundType, CurrentEnvironment, HomeItemType, SocketTypes, UserFileType } from './enum'

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
