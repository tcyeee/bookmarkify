import type { CurrentEnvironment, UserFileType } from './enum'

export interface LoginParams {
  account: string
  password: string
}

export interface UserAuthParams {
  deviceUid: string
  token?: string
}

export interface CaptchaSmsParams {
  phone: string
  captcha: string
}

export interface SmsVerifyParams {
  phone: string
  smsCode: string
}

export interface UserFile {
  environment: CurrentEnvironment
  currentName: string
  type: UserFileType
}

export interface UserInfoUpdate {
  nickName?: string
  phone?: string
}
