// GoatCounter 埋点（自托管，按 Host 路由）。
//
// 脚本与上报端点走「同源首方」相对路径 /count.js 与 /count，由 nginx
// (bookmakify.cc.conf) 代理到 goatcounter 容器，因此页面不暴露第三方域名。
//
// 所有埋点逻辑收口在本插件：业务代码只调用 useNuxtApp().$track('event-name')，
// 不直接触碰 GoatCounter API。dev / localhost 下天然 no-op（不加载脚本，
// $track 静默丢弃），生产环境才真正上报。

type GoatCounter = {
  count: (vars: { path?: string; title?: string; event?: boolean }) => void
  no_onload?: boolean
}

declare global {
  interface Window {
    goatcounter?: GoatCounter
  }
}

export default defineNuxtPlugin(() => {
  // 关键行为事件上报；脚本未加载（dev / 加载失败）时静默 no-op
  const track = (name: string, title?: string) => {
    window.goatcounter?.count({ path: name, title: title ?? name, event: true })
  }

  if (!import.meta.dev) {
    const router = useRouter()
    let lastPath = ''

    // SPA 路由切换时手动上报 pageview（no_onload 关闭了自动首屏计数，
    // 由此处作为唯一来源，避免首屏被重复计数）
    const trackPageview = () => {
      const path = location.pathname + location.search + location.hash
      if (path === lastPath) return
      lastPath = path
      window.goatcounter?.count({ path })
    }

    // 必须在 count.js 加载前置 no_onload，禁用其默认的首屏自动计数
    window.goatcounter = { ...(window.goatcounter ?? {}), no_onload: true } as GoatCounter

    const script = document.createElement('script')
    script.async = true
    script.src = '/count.js'
    script.setAttribute('data-goatcounter', '/count')
    script.onload = () => trackPageview() // 首屏
    document.head.appendChild(script)

    // 初次渲染的路由守卫不一定触发，故首屏由 onload 兜底，后续切换走 afterEach
    router.afterEach(() => nextTick(trackPageview))
  }

  return {
    provide: { track },
  }
})

// 显式声明 $track，便于业务代码 useNuxtApp().$track(...) 获得类型提示
declare module 'nuxt/app' {
  interface NuxtApp {
    $track: (name: string, title?: string) => void
  }
}
declare module 'vue' {
  interface ComponentCustomProperties {
    $track: (name: string, title?: string) => void
  }
}
