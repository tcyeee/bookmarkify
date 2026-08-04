import { AuthStatusEnum } from '@typing'
import { useAuthStore } from '@stores/auth.store'
import { usePreferenceStore } from '@stores/preference.store'

export default defineNuxtPlugin(async () => {
  const authStore = useAuthStore()
  const preferenceStore = usePreferenceStore()
  console.log(`DEBUG: 帐户状态为: ${authStore.authStatus}`)

  // 客户端从 localStorage 还原背景图缓存(state 初始化为 null 以避免 hydration 不一致)
  if (import.meta.client) preferenceStore.hydrateBackgroundCache()

  if (authStore.authStatus === AuthStatusEnum.NONE) return

  // 如果用户已登录,先连接 WebSocket(若 token 已过期,refreshUserInfo 会触发 logout 并
  // 跳转 /welcome,不会自动重新登录;WebSocket store 的 connect 会在 token 变化时自动
  // 关闭旧连接并重建)。
  const webSocketStore = useWebSocketStore()
  webSocketStore.connect(authStore.account!.token)

  // 并行加载用户信息 / 偏好 / 书签;refreshUserInfo 失败(如 202)会 logout 并跳转 /welcome,不会自动重连
  authStore.refreshUserInfo()
  preferenceStore.fetchPreference()
  const bookmarkStore = useBookmarkStore()
  // 缓存新鲜（2 分钟内拉取过且非空）时跳过全量拉取，避免不必要的重渲染；
  // 缺失或过期时正常拉取（首次加载 / 长时间不活跃 / 清除缓存后）。
  if (!bookmarkStore.isFresh()) bookmarkStore.refresh('页面加载')
  // localStorage 里可能恢复出上次会话没解析完的 LOADING 节点，而它们的兜底定时器是进程内状态、
  // 刷新即失效。偏偏"加完书签立刻 F5"这个最常见的动作必然落在上面那条「缓存新鲜、跳过拉取」的
  // 分支上——不在这里补挂，那些格子就没有任何人看着，只能干等一条可能早就发过了的 WebSocket 推送。
  bookmarkStore.armPendingWatches()
  // 同理，导入完成提示的 45 分钟超时兜底是纯时间戳比较——标签页全程关闭期间没有任何 nodes
  // 变化事件会触发 checkImportBatches，水合后必须主动补一次，否则超时判定要等到下一次全量拉取
  bookmarkStore.checkImportBatches()
})
