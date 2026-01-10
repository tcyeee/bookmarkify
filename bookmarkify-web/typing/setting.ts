import type {
  BackgroundType,
  BookmarkImageSize,
  BookmarkGapMode,
  BookmarkOpenMode,
  PageTurnMode,
} from './enum'
import type { UserFile, UserFileVO } from './user'

export interface UserPreferenceVO {
  bacSetting: BacSettingVO
}

export interface BacGradientVO {
  id?: string // 渐变背景配置ID（预设渐变需要用该ID进行选择）
  colors: string[] // 渐变色数组，至少2个颜色
  direction?: number // 渐变方向角度，默认135
}

export interface GradientConfigParams {
  id?: string // 自定义渐变ID（编辑时必填）
  colors: string[] // 渐变色数组，至少2个颜色
  direction?: number // 渐变方向角度，默认135
}

export interface BacSettingVO {
  type: BackgroundType
  backgroundLinkId: string
  bacImgFile?: UserFileVO

  bacColorGradient?: string[] // 当BackgroundType为GRADIENT时使用
  bacColorDirection?: number // 当BackgroundType为GRADIENT时使用
}

export interface BackSettingParams {
  type: BackgroundType
  backgroundId: string
}

export interface DefaultBackgroundsResponse {
  gradients: BacGradientVO[]
  images: UserFile[]
}

/**
 * 仅仅保存用户的状态信息,因为这里的信息会完全写在Session中,在每次请求的时候携带
 */
export interface UserInfo {
  uid: string // 用户唯一ID
  nickName: string // 用户昵称
  phone?: string // 用户手机号
  email?: string // 用户邮箱
  verified: boolean // 用户是否验证
  token: string // 用户TOKEN
}

export interface UserPreference {
  bookmarkOpenMode: BookmarkOpenMode
  minimalMode: boolean
  bookmarkGap: BookmarkGapMode
  bookmarkImageSize: BookmarkImageSize
  showTitle: boolean
  showDesktopAddEntry: boolean
  pageMode: PageTurnMode
  imgBacShow?: BacSettingVO | null
}
