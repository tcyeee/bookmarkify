import type { BackgroundType, CurrentEnvironment, HomeItemType, SocketTypes, UserFileType } from './enum'

export interface LoginParams {
  account: string
  password: string
}

export interface UserAuthParams {
  deviceUid: string
  token?: string
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
