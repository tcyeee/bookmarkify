// 前端版本自动升级。
//
// Nuxt 4 每次构建都会产出 `_nuxt/builds/latest.json`（内容是一个随构建变化的 buildId），
// 内置插件 `check-outdated-build.client` 按 `experimental.checkOutdatedBuildInterval`
// 轮询它，发现 id 变了就触发 `app:manifest:update`；另一个内置插件 `chunk-reload.client`
// 收到该钩子后给 router 挂一个 beforeResolve，**下一次路由跳转**时整页硬刷新。
//
// 那套默认行为对本站基本无效：书签桌面实际上只有 `/` 一个页面，用户几乎不发生路由跳转，
// 于是「等下次跳转再刷」等于永远不刷。本插件补两件事：
//   1. 切回标签页时主动比对一次 —— 定时器在后台标签里被浏览器节流，回到前台才是真正
//      需要判断新鲜度的时刻，也是用户手上没有拖拽/输入在进行的安全时刻。
//   2. 拿到结论后分两种处理：页面不可见就直接静默重载（用户回来即是新版，看不到闪烁），
//      可见则只弹提示，把「什么时候刷」交还给用户 —— 正在拖书签时把页面抽走比不升级更糟。
//
// 钩子照常 callHook 出去，Nuxt 内置的跳转刷新逻辑仍然生效，本插件只是叠加触发时机。
export default defineNuxtPlugin((nuxtApp) => {
  // dev 下 latest.json 不存在（appManifest 只在构建产物里），且热更新自会处理
  if (import.meta.dev) return

  const config = useRuntimeConfig()
  let notified = false

  const reload = () => {
    reloadNuxtApp({ path: nuxtApp._route.fullPath, persistState: true })
  }

  nuxtApp.hook('app:manifest:update', () => {
    if (notified) return
    notified = true

    if (document.hidden) {
      reload()
      return
    }
    // toast 默认 3s 太短，这条信息值得多停一会儿
    useToastStore().push('info', '检测到新版本，刷新页面即可更新', 8000)
  })

  const check = async () => {
    if (notified) return
    try {
      // 加时间戳绕开浏览器缓存 —— 与 Nuxt 内置插件同样的做法；nginx 侧另有 no-store 兜底
      const res = await fetch(`/_nuxt/builds/latest.json?${Date.now()}`)
      if (!res.ok) return
      const meta = (await res.json()) as { id?: string; timestamp?: number }
      if (meta.id && meta.id !== config.app.buildId) {
        nuxtApp.hooks.callHook('app:manifest:update', { id: meta.id, timestamp: meta.timestamp ?? Date.now() })
      }
    } catch {
      // 网络抖动 / 部署中途拿到 404 都不是错误，下次可见时再判
    }
  }

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) check()
  })
})
