import tailwindcss from "@tailwindcss/vite";
import { resolve } from 'path'
export default defineNuxtConfig({
  app: {
    head: {
      title: 'bookmarkify', // 默认标题
    },
  },
  alias: {
    '@api': resolve(__dirname, 'server/apis'),
    '@stores': resolve(__dirname, 'stores')
  },
  modules: [
    '@pinia/nuxt',
    '@element-plus/nuxt',
    'pinia-plugin-persistedstate/nuxt',
    // 'nuxt-vue3-google-signin'
  ],
  plugins: [
    '~/plugins/keyListener.ts',
    '~/plugins/contextMenu.ts',
    '~/plugins/auth.ts'
  ],
  vite: {
    plugins: [
      tailwindcss(),
    ],
  },
  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_API_BASE,
      wsBase: process.env.NUXT_WS_BASE
    }
  },
  build: {
    transpile: ['dayjs', 'element-plus'],
  },
  elementPlus: { /** Options */ },
  css: ['~/assets/css/app.css', '~/assets/css/common.scss', '~/assets/css/icon.scss'],
  devtools: { enabled: true },
  // pinia: {
  //   storesDirs: ['./stores/**'],
  // },
  compatibilityDate: '2025-02-12',
})