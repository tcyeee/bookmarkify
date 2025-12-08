import type { BackgroundType } from './enum'
import type { UserFile } from './user'

export interface UserSetting {
  bacSetting: BacSettingVO
}

export interface BacGradientVO {
  colors: string[] // 渐变色数组，至少2个颜色
  direction?: number // 渐变方向角度，默认135
}

export interface BacSettingVO {
  type: BackgroundType
  bacImgFile?: UserFile // 当BackgroundType为IMAGE时使用

  bacColorGradient?: string[] // 当BackgroundType为GRADIENT时使用
  bacColorDirection?: number // 当BackgroundType为GRADIENT时使用
}

export interface UserInfoEntity {
  uid: string
  token: string
  nickName: string
  phone?: string
  email?: string
  verified: boolean
  avatar: UserFile
  userSetting: UserSetting
}
