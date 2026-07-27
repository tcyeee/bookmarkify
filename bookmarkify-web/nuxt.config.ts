import tailwindcss from '@tailwindcss/vite'
import Icons from 'unplugin-icons/vite'
import { resolve } from 'path'

const siteUrl = process.env.NUXT_PUBLIC_SITE_URL || 'https://bookmarkify.cc'
const seoDescription =
  '集中收藏应用、工具与灵感；一键保存、智能分类与搜索、云端同步、书签活性检测，以及社区分享与发现，随时随地一触即达。'
const seoKeywords = 'bookmarkify,书签鸭,书签管理,云端同步,一键收藏,智能分类,模糊搜索,书签检测,书签分享,社区发现,效率工具'
export default defineNuxtConfig({
  app: {
    head: {
      // title: 'Bookmarkify 管理、分享你的应用书签',
      title: '便捷书签管理工具',
      htmlAttrs: {
        lang: 'zh-CN',
      },
      meta: [
        { name: 'description', content: seoDescription },
        { name: 'keywords', content: seoKeywords },
        { name: 'author', content: 'tcyeee' },
        { name: 'robots', content: 'index, follow' },
        { name: 'google', content: 'notranslate' },
        { name: 'application-name', content: 'Bookmarkify 书签鸭' },
        { name: 'apple-mobile-web-app-title', content: 'Bookmarkify 书签鸭' },
        { name: 'format-detection', content: 'telephone=no,email=no,address=no' },
        { name: 'theme-color', content: '#0b1220' },
        // Open Graph
        { property: 'og:type', content: 'website' },
        { property: 'og:site_name', content: 'Bookmarkify 书签鸭' },
        { property: 'og:title', content: 'Bookmarkify 书签鸭 | 管理、分享你的应用书签' },
        { property: 'og:description', content: seoDescription },
        { property: 'og:url', content: siteUrl },
        { property: 'og:image', content: `${siteUrl}/favicon.ico` },
        // Twitter Card
        { name: 'twitter:card', content: 'summary_large_image' },
        { name: 'twitter:title', content: 'Bookmarkify 书签鸭 | 管理、分享你的应用书签' },
        { name: 'twitter:description', content: seoDescription },
        { name: 'twitter:image', content: `${siteUrl}/favicon.ico` },
      ],
      link: [
        { rel: 'canonical', href: `${siteUrl}/welcome` },
        { rel: 'icon', type: 'image/x-icon', href: '/favicon.ico' },
      ],
      // 埋点改用自托管 GoatCounter，脚本加载收口在 plugins/analytics.client.ts
    },
  },
  alias: {
    '@api': resolve(__dirname, 'server/apis'),
    '@stores': resolve(__dirname, 'stores'),
    '@config': resolve(__dirname, 'server/config'),
    '@typing': resolve(__dirname, 'typing'),
    '@utils': resolve(__dirname, 'server/utils'),
  },
  // 登录态只存在于客户端 localStorage（auth.store 持久化）。受 auth 中间件保护的页面
  // 若在 `nuxt generate` 阶段 SSR 预渲染，服务端读不到登录态 → 中间件重定向到 /welcome，
  // 该重定向会被烘焙进静态 HTML（<meta http-equiv="refresh" url=/welcome>）。刷新首页时
  // 浏览器先加载 welcome 再由客户端水合后跳回，造成约 1s 的 welcome 闪屏。
  // 将这些页面改为纯客户端渲染（ssr:false），静态产物只是空壳，登录判定在首屏绘制前于
  // 客户端完成，从而消除闪屏；/welcome 仍保留 SSR 以保证落地页 SEO。
  routeRules: {
    '/': { ssr: false },
    '/setting': { ssr: false },
    '/bookmark/similar': { ssr: false },
  },
  // 首页改为 SPA 后爬虫无法再从 `/` 发现 /welcome，需显式声明以保证落地页仍被预渲染（SEO）。
  nitro: {
    prerender: {
      routes: ['/welcome'],
    },
  },
  modules: ['@pinia/nuxt', 'pinia-plugin-persistedstate/nuxt', '@nuxtjs/i18n'],
  i18n: {
    // 暂时支持中/英/日/法四种语言；策略为 no_prefix（不带 URL 前缀），
    // 语言切换在偏好设置中完成，选择结果通过 cookie 持久化。
    strategy: 'no_prefix',
    defaultLocale: 'zh-CN',
    langDir: 'locales/',
    lazy: true,
    locales: [
      { code: 'zh-CN', name: '简体中文', file: 'zh-CN.json' },
      { code: 'en', name: 'English', file: 'en.json' },
      { code: 'ja', name: '日本語', file: 'ja.json' },
      { code: 'fr', name: 'Français', file: 'fr.json' },
    ],
    detectBrowserLanguage: {
      useCookie: true,
      cookieKey: 'bookmarkify_locale',
    },
  },
  plugins: [
    '~/plugins/iconify.ts',
    '~/plugins/keyListener.ts',
    '~/plugins/contextMenu.ts',
    '~/plugins/auth.ts',
    '~/plugins/analytics.client.ts',
  ],
  vite: {
    // 按需从 @iconify-json/* 打包图标（如 mdi），只编译代码中实际用到的图标，
    // 避免像 memory 图标集那样把整套 icon 集合全量塞进运行时 bundle。
    plugins: [tailwindcss(), Icons({ compiler: 'vue3' })],
    // 生产构建剔除调试日志（保留 console.warn/error 以便线上排障）
    esbuild: {
      pure: process.env.NODE_ENV === 'production' ? ['console.log', 'console.debug'] : [],
    },
  },
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_API_BASE,
      wsBase: process.env.NUXT_WS_BASE,
      siteUrl,
      googleClientId: process.env.NUXT_PUBLIC_GOOGLE_CLIENT_ID,
      githubClientId: process.env.NUXT_PUBLIC_GITHUB_CLIENT_ID,
    },
  },
  build: {
    transpile: ['dayjs'],
  },
  css: ['~/assets/css/app.css', '~/assets/css/common.scss', '~/assets/css/icon.scss'],
  devtools: { enabled: true },
  // pinia: {
  //   storesDirs: ['./stores/**'],
  // },
  compatibilityDate: '2025-02-12',
})
