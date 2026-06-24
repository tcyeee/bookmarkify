import http from './http'
import type * as t from '@typing'

/* =========[ /auth ]========= */
export const authLogout = () => http.get<void>('/auth/logout')
export const captchaSendEmail = (params: t.CaptchaEmailParams) => http.post<string>('/auth/captcha/email', params)
export const captchaVerifyEmail = (params: t.EmailVerifyParams) => http.post<t.UserInfo>('/auth/captcha/verifyEmail', params)
export const authLoginByAccount = (params: t.LoginParams) => http.post<t.UserInfo>('/auth/login', params)
export const authLoginByGoogle = (params: t.GoogleLoginParams) => http.post<t.UserInfo>('/auth/google', params)

/* =========[ /bookmark ]========= */
export const bookmarksShowAll = () => http.post<t.UserLayoutNodeVO>('/bookmark/query')
export const bookmarksSearch = (name: string) => http.post<Array<any>>(`/bookmark/search?name=${encodeURIComponent(name)}`)
export const bookmarksAddOne = (url: string) => http.get<t.UserLayoutNodeVO>('/bookmark/addOne', { url: url })
export const bookmarksLinkOne = (bookmarkId: string) => http.get<t.UserLayoutNodeVO>('/bookmark/linkOne', { bookmarkId })
export const bookmarksSort = (params: Record<string, number>) => http.post<boolean>('/bookmark/sort', params)
export const bookmarksDel = (params: Array<string>) => http.post<boolean>('/bookmark/delete', params)
export const bookmarksUpdate = (params: t.BookmarkUpdatePrams) => http.post<t.BookmarkShow>('/bookmark/update', params)
export const bookmarksUploadPreview = (file: File) =>
  http.upload<t.BookmarkImportPreviewVO>('/bookmark/upload/preview', file)

export const bookmarksUpload = (file: File, skipUrls: string[]) =>
  http.uploadWithForm<t.UserLayoutNodeVO[]>('/bookmark/upload', file, { skipUrls })
export const bookmarksList = (params?: t.BookmarkListParams) =>
  http.post<t.BookmarkPage<t.BookmarkShow>>('/bookmark/list', params ?? {})
export const bookmarksCreateDir = (nodeIds: [string, string], name: string, sort: number) =>
  http.post<t.UserLayoutNodeVO>('/bookmark/createDir', { nodeIds, name, sort })
export const bookmarksRenameDir = (nodeId: string, name: string) =>
  http.post<boolean>('/bookmark/renameDir', { nodeId, name })
export const bookmarksMoveNode = (nodeId: string, dirNodeId: string | null) =>
  http.post<t.UserLayoutNodeVO>('/bookmark/moveNode', { nodeId, dirNodeId })

/* =========[ /user ]========= */
export const updateUserInfo = (param: t.UserInfoUpdate) => http.post<boolean>('/user/updateInfo', param)
export const queryUserInfo = () => http.get<t.UserInfo>('/user/info')
export const queryAvatarUrl = () => http.get<string | null>('/user/avatar-url')
export const accountDelete = (email?: string) => http.post<boolean>('/user/del', { email })
export const uploadAvatar = (file: File) => http.upload<string>('/user/uploadAvatar', file)
export const bindGoogle = (idToken: string) => http.post<t.UserInfo>('/user/google/bind', { idToken })
export const unbindGoogle = () => http.post<t.UserInfo>('/user/google/unbind')
export const authLoginByGithub = (params: t.GithubLoginParams) => http.post<t.UserInfo>('/auth/github', params)
export const bindGithub = (code: string, redirectUri: string) => http.post<t.UserInfo>('/user/github/bind', { code, redirectUri })
export const unbindGithub = () => http.post<t.UserInfo>('/user/github/unbind')

/* =========[ /setting ]========= */
export const uploadBacPic = (file: File) => http.upload<string>('/background/uploadBacPic', file)
export const updateBacColor = (params: t.BacGradientVO) => http.post<boolean>('/background/updateBacColor', params)
export const defaultBackgrounds = () => http.get<t.DefaultBackgroundsResponse>('/background/default')
export const defaultImageBackgrounds = async () => (await defaultBackgrounds()).images
export const defaultGradientBackgrounds = async () => (await defaultBackgrounds()).gradients
export const myBackgrounds = () => http.get<t.DefaultBackgroundsResponse>('/background/mine')
export const resetBacBackground = () => http.get<boolean>('/background/background/reset')
export const selectBackground = (params: t.BackSettingParams) =>
  http.post<t.BacSettingVO>('/background/selectBackground', params)
export const updateGradientBackground = (params: t.GradientConfigParams) =>
  http.post<boolean>('/background/gradient/update', params)
export const deleteGradientBackground = (id: string) => http.start<boolean>(`/background/gradient/${id}`, 'DELETE')

/* =========[ /preference ]========= */
export const queryUserPreference = () => http.get<t.UserPreference | null>('/preference')
export const updateUserPreference = (params: t.UserPreference) => http.post<boolean>('/preference', params)
