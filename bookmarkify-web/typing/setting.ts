import type { BackgroundType } from './enum'
import type { UserFile } from './typing'

export interface UserSetting {
  backgroundSetting: BackgroundConfig
}

export interface BackgroundGradientEntity {
  colors: string[] // 渐变色数组，至少2个颜色
  direction?: number // 渐变方向角度，默认135
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
