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
  if (!bookmarkStore.isFresh()) bookmarkStore.update()
})
