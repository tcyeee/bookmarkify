export default defineNuxtPlugin(() => {
  if (!import.meta.client) return

  const userStore = useUserStore()
  const bookmarkStore = useBookmarkStore()
  const sysStore = useSysStore()

  // 获取用户信息
  // void userStore.refreshUserInfo()
  // 获取书签
  // void bookmarkStore.update()
  // 获取系统默认配置信息
  // void sysStore.refreshSystemConfig()
})
