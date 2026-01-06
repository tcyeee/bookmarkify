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
export const bookmarksSearch = (name: string) => http.post(`/bookmark/search?name=${encodeURIComponent(name)}`) as Promise<Array<any>>
export const bookmarksAddOne = (url: string) => http.get('/bookmark/addOne', { url: url }) as Promise<t.HomeItem>
export const bookmarksLinkOne = (bookmarkId: string) => http.get('/bookmark/linkOne', { bookmarkId }) as Promise<t.HomeItem>
export const bookmarksSort = (params: Array<t.BookmarkSortParams>) => http.post('/bookmark/sort', params) as Promise<boolean>
export const bookmarksDel = (params: Array<string>) => http.post('/bookmark/delete', params) as Promise<boolean>
export const bookmarksUpdate = (params: t.BookmarkUpdatePrams) => http.post('/bookmark/update', params) as Promise<t.Bookmark>
export const bookmarksUpload = (file: File) => http.upload('/bookmark/upload', file) as Promise<Array<t.Bookmark>>
export const bookmarksList = (params?: t.BookmarkListParams) =>
  http.post('/bookmark/list', params ?? {}) as Promise<Array<t.Bookmark>>

/* =========[ /user ]========= */
export const updateUserInfo = (param: t.UserInfoUpdate) => http.post('/user/updateInfo', param) as Promise<boolean>
export const queryUserInfo = () => http.get('/user/info') as Promise<t.UserInfo>
export const accountDelete = (pwd: string) => http.post('/user/del', { password: btoa(pwd) }) as Promise<boolean>
export const uploadAvatar = (file: File) => http.upload('/user/uploadAvatar', file) as Promise<string>

/* =========[ /setting ]========= */
export const uploadBacPic = (file: File) => http.upload('/background/uploadBacPic', file) as Promise<string>
export const updateBacColor = (params: t.BacGradientVO) => http.post('/background/updateBacColor', params) as Promise<boolean>
export const defaultBackgrounds = () => http.get('/background/default') as Promise<t.DefaultBackgroundsResponse>
export const defaultImageBackgrounds = async () => (await defaultBackgrounds()).images
export const defaultGradientBackgrounds = async () => (await defaultBackgrounds()).gradients
export const myBackgrounds = () => http.get('/background/mine') as Promise<t.DefaultBackgroundsResponse>
export const resetBacBackground = () => http.get('/background/background/reset') as Promise<boolean>
export const selectBackground = (params: t.BackSettingParams) =>
  http.post('/background/selectBackground', params) as Promise<t.BacSettingVO>
export const updateGradientBackground = (params: t.GradientConfigParams) =>
  http.post('/background/gradient/update', params) as Promise<boolean>
export const deleteGradientBackground = (id: string) =>
  http.start(`/background/gradient/${id}`, 'DELETE') as Promise<boolean>

/* =========[ /preference ]========= */
export const queryUserPreference = () => http.get('/preference') as Promise<t.UserPreference | null>
export const updateUserPreference = (params: t.UserPreference) => http.post('/preference', params) as Promise<boolean>
