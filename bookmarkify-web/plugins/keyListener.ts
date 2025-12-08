export default defineNuxtPlugin(() => {
  if (!import.meta.client) return
  const sysStore = useSysStore()

  // 阻止鼠标右键
  window.addEventListener('contextmenu', (event) => {
    event.preventDefault()
  })

  // 检测键盘任意按键按下
  window.addEventListener('keydown', (event) => {
    sysStore.triggerKeyEvent(event.code, useRoute().path)
  })

  // 注册基础按键功能
  sysStore.registerKeyEvent('Escape', '/setting', () => navigateTo('/'))
})
