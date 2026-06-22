import type { CurrentEnvironment, UserFileType } from './enum'

export interface LoginParams {
  account: string
  password: string
}

export interface CaptchaEmailParams {
  email: string
}

export interface EmailVerifyParams {
  email: string
  code: string
}

export interface GoogleLoginParams {
  idToken: string
}

export interface GithubLoginParams {
  code: string
  redirectUri: string
}

export interface UserFile {
  environment: CurrentEnvironment
  currentName: string
  type: UserFileType
}

export interface UserFileVO {
  id: string
  fullName: string
}

export interface UserInfoUpdate {
  nickName?: string
  email?: string
}

export interface LoginMethod {
  key: 'email' | 'password'
  label: string
  icon: string
  description: string
}
