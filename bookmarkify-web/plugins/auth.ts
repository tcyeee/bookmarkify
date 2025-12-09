export default defineNuxtPlugin(async () => {
  const userStore = useUserStore()
  const bookmarkStore = useBookmarkStore()
  const webSocketStore = useWebSocketStore()

  // 先登录
  await userStore.login()
  // 然后注册WebSocket
  if (!userStore.account?.token) throw new Error('[ERROR] 登录状态异常')
  webSocketStore.connect(userStore.account.token)
  // 然后更新书签
  await bookmarkStore.update()
  // 然后更新用户基础信息
})
