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

/**
 * 这里比较特殊，对应到后端其实是两个不同的实体类
 * UserInfoShow 和 UserSessionInfo
 */
export interface UserInfo {
  uid: string // 用户唯一ID
  nickName: string // 用户昵称
  phone?: string // 用户手机号
  email?: string // 用户邮箱
  verified: boolean // 用户是否验证

  /* 仅/tract接口会返回 */
  token: string // 用户TOKEN

  /* 仅/info接口会返回 */
  avatar: UserFile // 用户头像
  userSetting: UserSetting // 用户设置
}
