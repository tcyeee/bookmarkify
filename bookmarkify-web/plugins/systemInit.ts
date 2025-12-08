export default defineNuxtPlugin(() => {
  if (!import.meta.client) return
  const userStore = useUserStore()
  const bookmarkStore = useBookmarkStore()
  const sysStore = useSysStore()

  // 获取用户信息
  void userStore.getUserInfo()
  // 获取书签
  void bookmarkStore.update()
  // 获取系统设置
  //   void sysStore.getSystemSetting()
})
