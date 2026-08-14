import http from './http'
import type * as t from '@typing'

/* =========[ /auth ]========= */
export const authLogout = () => http.get<void>('/auth/logout')
export const captchaSendEmail = (params: t.CaptchaEmailParams) => http.post<string>('/auth/captcha/email', params)
export const captchaVerifyEmail = (params: t.EmailVerifyParams) => http.post<t.UserInfo>('/auth/captcha/verifyEmail', params)
export const authLoginByAccount = (params: t.LoginParams) => http.post<t.UserInfo>('/auth/login', params)
export const authLoginByGoogle = (params: t.GoogleLoginParams) => http.post<t.UserInfo>('/auth/google', params)
export const authQuickLogin = () => http.get<t.UserInfo>('/auth/quickLogin')

/* =========[ /bookmark ]========= */
export const bookmarksShowAll = () => http.post<t.UserLayoutNodeVO>('/bookmark/query')
export const bookmarksSearch = (name: string) => http.post<Array<any>>(`/bookmark/search?name=${encodeURIComponent(name)}`)
// addOne 会创建 bookmark/user_layout_node/bookmark_user_link 三张表的写入，改用 POST 承载（与 bookmarksSearch 一致的写法：
// query string 传参 + POST 方法），避免 GET 请求被浏览器预取/代理缓存/爬虫意外重放触发非预期写操作
export const bookmarksAddOne = (url: string) => http.post<t.UserLayoutNodeVO>(`/bookmark/addOne?url=${encodeURIComponent(url)}`)
// 与 addOne 同理：linkOne 也会写 user_layout_node + bookmark_user_link 两张表，改用 POST 承载。
// 参数名是 pageId 而不是 bookmarkId：后端要的是 canonical 页面记录的 id（搜索结果 BookmarkSearchVO.id
// 就是 PageEntity.id），"bookmark" 在重命名后专指用户与页面的关联行。名字写错时 Spring 会因缺必填参数
// 抛异常、被兜成 E999「服务器繁忙」，看不出是参数问题。
export const bookmarksLinkOne = (pageId: string) =>
  http.post<t.UserLayoutNodeVO>(`/bookmark/linkOne?pageId=${encodeURIComponent(pageId)}`)
export const bookmarksSort = (params: Record<string, number>) => http.post<boolean>('/bookmark/sort', params)
export const bookmarksDel = (params: Array<string>) => http.post<boolean>('/bookmark/delete', params)
// 返回的是「更新成功与否」，**不是**更新后的书签（BookmarksController.update: Boolean）。
// 这里曾声明成 BookmarkShow，于是调用方读 res.title / res.description 永远拿到 undefined，
// 保存完那一格的标题与描述就被清空了 —— 类型是编出来的，tsc 自然一句话都不会说。
export const bookmarksUpdate = (params: t.BookmarkUpdatePrams) => http.post<boolean>('/bookmark/update', params)
export const bookmarksPin = (linkId: string, pinned: boolean) => http.post<boolean>('/bookmark/pin', { linkId, pinned })
// 让后端重新抓取这条书签。**只投递、不等待**：抓取要几秒到几十秒，接口立刻返回，结果稍后经
// WebSocket HOME_ITEM_UPDATE 推回来，由 bookmarkStore.replaceContent() 就地替换那一格。
// 因此调用方拿到的 true 只代表「已排进后台队列」，不代表抓到了东西。
export const bookmarksRefetch = (linkId: string) =>
  http.post<boolean>(`/bookmark/refetch?linkId=${encodeURIComponent(linkId)}`)
// 书签封面（页面截图优先，退 og:image）。按需单取而不是随桌面列表下发：桌面可能有几百条
// 书签，而封面只在点开某一条时才看得到，给每条都带一个几百字节的签名 URL 是纯浪费。
// 没有封面时返回 null，此时不该渲染任何占位。
export const bookmarksCover = (linkId: string) =>
  http.post<string | null>(`/bookmark/cover?linkId=${encodeURIComponent(linkId)}`)
// 仅做打开次数记录，fire-and-forget 调用，不阻塞书签的实际打开
export const bookmarksRecordOpen = (linkId: string) => http.post<boolean>('/bookmark/open', { linkId })
export const bookmarksUploadPreview = (file: File) =>
  http.upload<t.BookmarkImportPreviewVO>('/bookmark/upload/preview', file)

export const bookmarksUpload = (file: File, skipUrls: string[]) =>
  http.uploadWithForm<t.UserLayoutNodeVO[]>('/bookmark/upload', file, { skipUrls })
export const bookmarksList = (params?: t.BookmarkListParams) =>
  http.post<t.BookmarkPage<t.BookmarkShow>>('/bookmark/list', params ?? {})
export const bookmarksCreateDir = (nodeIds: string[], name: string, sort: number) =>
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

/* =========[ /share ]========= */
export const shareCreate = (params: t.ShareCreateParams) => http.post<t.ShareVO>('/share/create', params)
export const shareView = (code: string) => http.get<t.SharePublicVO>('/share/view', { code })
export const shareMine = (params?: t.ShareListParams) => http.post<t.BookmarkPage<t.ShareVO>>('/share/mine', params ?? {})
export const shareCancel = (id: string) => http.post<boolean>(`/share/cancel?id=${encodeURIComponent(id)}`)
export const shareUpdate = (params: t.ShareUpdateParams) => http.post<t.ShareVO>('/share/update', params)

/* =========[ /user/access-token ]========= */
export const accessTokenCreate = (params: t.AccessTokenCreateParams) =>
  http.post<t.AccessTokenCreatedVO>('/user/access-token/create', params)
export const accessTokenList = () => http.get<t.AccessTokenVO[]>('/user/access-token/list')
export const accessTokenRevoke = (id: string) => http.post<boolean>(`/user/access-token/revoke?id=${encodeURIComponent(id)}`)
