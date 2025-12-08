import http from './http/http'
import type * as t from './typing'

/* =========[ /bookmark ]========= */
export const bookmarksShowAll = () => http.post('/bookmark/query') as Promise<Array<t.HomeItem>>
export const bookmarksAddOne = (params: t.bookmarksAddOneParams) => http.post('/bookmark/addOne', params) as Promise<t.HomeItem>
export const bookmarksSort = (params: Array<t.BookmarkSortParams>) => http.post('/bookmark/sort', params) as Promise<boolean>
export const bookmarksDel = (params: Array<string>) => http.post('/bookmark/delete', params) as Promise<boolean>
export const bookmarksUpdate = (params: t.BookmarkUpdatePrams) => http.post('/bookmark/update', params) as Promise<t.Bookmark>

/* =========[ /user ]========= */
export const updateUserInfo = (param: t.UserInfoUpdate) => http.post('/user/updateInfo', param) as Promise<boolean>
export const queryUserInfo = () => http.get('/user/info') as Promise<t.UserInfoEntity>
export const accountDelete = (pwd: string) => http.post('/user/del', { password: btoa(pwd) }) as Promise<boolean>
export const uploadAvatar = (file: File) => http.upload('/user/uploadAvatar', file) as Promise<string>

/* =========[ /setting ]========= */
export const uploadBacPic = (file: File) => http.upload('/setting/uploadBacPic', file) as Promise<string>
export const updateBacColor = (params: t.GradientConfig) => http.post('/setting/updateBacColor', params) as Promise<boolean>
