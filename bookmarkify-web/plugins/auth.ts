import { AuthStatusEnum } from '@typing'
import { useAuthStore } from '@stores/auth.store'
import { usePreferenceStore } from '@stores/preference.store'

export default defineNuxtPlugin(async () => {
  const authStore = useAuthStore()
  const preferenceStore = usePreferenceStore()
  console.log(`DEBUG: 帐户状态为: ${authStore.authStatus}`)

  if (authStore.authStatus !== AuthStatusEnum.NONE) {
    // 如果用户已登录，则连接WebSocket
    const webSocketStore = useWebSocketStore()
    webSocketStore.connect(authStore.account!.token)

    // 如果用户已经登录,则更新用户权限信息
    authStore.refreshUserInfo()
    // 如果用户已经登录,则更新用户偏好设置
    preferenceStore.fetchPreference()

    // 如果用户已经登录,则更新书签信息
    const bookmarkStore = useBookmarkStore()
    bookmarkStore.update()
  }
})
