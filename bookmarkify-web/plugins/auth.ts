import { AuthStatusEnum } from '@typing'

export default defineNuxtPlugin(async () => {
  const userStore = useUserStore()
  const webSocketStore = useWebSocketStore()

  /*
   * 初次进入APP判断是否已经有帐户，如果没有则跳转到首页
   *     ∟ 如果有帐户，则跳转到APP页面
   *     ∟ 如果没有帐户，则跳转到首页
   */
  console.log('[DEBUG] 帐户状态为：', userStore.authStatus)
  if (userStore.authStatus === AuthStatusEnum.AUTHED) {
    // return navigateTo('/')
  }

  // 先登录
  // await userStore.loginOrRegister()
  // 然后注册WebSocket
  // if (!userStore.account?.token) throw new Error('[ERROR] 登录状态异常')
  // webSocketStore.connect(userStore.account.token)
})
