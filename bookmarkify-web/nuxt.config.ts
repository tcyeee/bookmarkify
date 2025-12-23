import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'

const siteUrl = process.env.NUXT_PUBLIC_SITE_URL || 'https://bookmarkify.app'
const seoDescription =
  '集中收藏应用、工具与灵感；一键保存、智能分类与搜索、云端同步、书签活性检测，以及社区分享与发现，随时随地一触即达。'
const seoKeywords = 'bookmarkify,书签鸭,书签管理,云端同步,一键收藏,智能分类,模糊搜索,书签检测,书签分享,社区发现,效率工具'
export default defineNuxtConfig({
  app: {
    head: {
      title: 'Bookmarkify 书签鸭 | 管理、分享你的应用书签',
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
        { rel: 'canonical', href: siteUrl },
        { rel: 'icon', type: 'image/x-icon', href: '/favicon.ico' },
      ],
      script: [
        {
          src: 'https://cloud.umami.is/script.js',
          defer: true,
          'data-website-id': '53ab5e59-d09c-4ce0-9265-6722eea41842',
        },
      ],
    },
  },
  alias: {
    '@api': resolve(__dirname, 'server/apis'),
    '@stores': resolve(__dirname, 'stores'),
    '@config': resolve(__dirname, 'server/config'),
    '@typing': resolve(__dirname, 'typing'),
    '@utils': resolve(__dirname, 'server/utils'),
  },
  modules: ['@pinia/nuxt', '@element-plus/nuxt', 'pinia-plugin-persistedstate/nuxt'],
  plugins: ['~/plugins/keyListener.ts', '~/plugins/contextMenu.ts', '~/plugins/auth.ts', '~/plugins/systemInit.ts'],
  vite: {
    plugins: [tailwindcss()],
  },
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_API_BASE,
      wsBase: process.env.NUXT_WS_BASE,
      siteUrl,
    },
  },
  build: {
    transpile: ['dayjs', 'element-plus'],
  },
  elementPlus: {
    /** Options */
  },
  css: ['~/assets/css/app.css', '~/assets/css/common.scss', '~/assets/css/icon.scss'],
  devtools: { enabled: true },
  // pinia: {
  //   storesDirs: ['./stores/**'],
  // },
  compatibilityDate: '2025-02-12',
})
