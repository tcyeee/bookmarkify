export default defineNuxtPlugin(() => {
  if (!import.meta.client) return

  // 阻止鼠标右键
  window.addEventListener('contextmenu', (event) => {
    event.preventDefault() // 阻止右键菜单
  })

  // 检测键盘
  window.addEventListener('keydown', (event) => {
    console.log('Key pressed:', event.key)
  })
})