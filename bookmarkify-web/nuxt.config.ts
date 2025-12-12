import tailwindcss from '@tailwindcss/vite'
import { resolve } from 'path'
export default defineNuxtConfig({
  app: {
    head: {
      title: 'bookmarkify 书签鸭',
      meta: [
        { name: 'description', content: 'bookmarkify 书签鸭，一个现代化的书签管理平台' },
        { name: 'keywords', content: 'bookmarkify,书签鸭,书签管理,书签分享,书签发现,书签社区' },
        { name: 'author', content: 'tcyeee' },
        { name: 'robots', content: 'index, follow' },
        { name: 'google', content: 'notranslate' },
      ],
      link: [{ rel: 'icon', type: 'image/x-icon', href: '/assets/logo/logo-lg.png' }],
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
