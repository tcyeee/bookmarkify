import http from './http'
import type * as t from '@typing'

/* =========[ /auth ]========= */
export const track = () => http.get('/auth/track') as Promise<t.UserInfo>
export const authLogout = () => http.get('/auth/logout') as Promise<void>
export const captchaImage = () => http.get('/auth/captcha/image') as Promise<string>
export const captchaSendSms = (params: t.CaptchaSmsParams) => http.post('/auth/captcha/sms', params) as Promise<boolean>
export const captchaVerifySms = (params: t.SmsVerifyParams) => http.post('/auth/captcha/verifySms', params) as Promise<t.UserInfo>
export const captchaSendEmail = (params: t.CaptchaEmailParams) => http.post('/auth/captcha/email', params) as Promise<boolean>
export const captchaVerifyEmail = (params: t.EmailVerifyParams) =>
  http.post('/auth/captcha/verifyEmail', params) as Promise<t.UserInfo>

/* =========[ /bookmark ]========= */
export const bookmarksShowAll = () => http.post('/bookmark/query') as Promise<Array<t.HomeItem>>
export const bookmarksAddOne = (url: string) => http.get('/bookmark/addOne', url) as Promise<t.HomeItem>
export const bookmarksSort = (params: Array<t.BookmarkSortParams>) => http.post('/bookmark/sort', params) as Promise<boolean>
export const bookmarksDel = (params: Array<string>) => http.post('/bookmark/delete', params) as Promise<boolean>
export const bookmarksUpdate = (params: t.BookmarkUpdatePrams) => http.post('/bookmark/update', params) as Promise<t.Bookmark>

/* =========[ /user ]========= */
export const updateUserInfo = (param: t.UserInfoUpdate) => http.post('/user/updateInfo', param) as Promise<boolean>
export const queryUserInfo = () => http.get('/user/info') as Promise<t.UserInfo>
export const accountDelete = (pwd: string) => http.post('/user/del', { password: btoa(pwd) }) as Promise<boolean>
export const uploadAvatar = (file: File) => http.upload('/user/uploadAvatar', file) as Promise<string>

/* =========[ /setting ]========= */
export const queryUserSetting = () => http.get('/setting/query') as Promise<t.UserSetting>
export const uploadBacPic = (file: File) => http.upload('/setting/uploadBacPic', file) as Promise<string>
export const updateBacColor = (params: t.BacGradientVO) => http.post('/setting/updateBacColor', params) as Promise<boolean>
export const defaultImageBackgrounds = () => http.get('/setting/background/images') as Promise<Array<t.UserFile>>
export const defaultGradientBackgrounds = () => http.get('/setting/background/gradients') as Promise<Array<t.BacGradientVO>>
export const resetBacBackground = () => http.get('/setting/background/reset') as Promise<boolean>
