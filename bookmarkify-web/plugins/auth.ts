import { AuthStatusEnum } from '@typing'

export default defineNuxtPlugin(async () => {
  const userStore = useUserStore()
  console.log(`DEBUG: 帐户状态为: ${userStore.authStatus}`)

  if (userStore.authStatus !== AuthStatusEnum.NONE) {
    // 如果用户已登录，则连接WebSocket
    const webSocketStore = useWebSocketStore()
    webSocketStore.connect(userStore.account!.token)

    // 如果用户已经登录,则更新用户权限信息
    userStore.refreshUserInfo()
    // 如果用户已经登录,则更新用户偏好设置
    userStore.fetchPreference()

    // 如果用户已经登录,则更新书签信息
    const bookmarkStore = useBookmarkStore()
    bookmarkStore.update()
  }
})
