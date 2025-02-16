export default defineNuxtConfig({
  app: {
    head: {
      title: 'bookmarkify', // 默认标题
    },
  },
  modules: [
    '@nuxtjs/tailwindcss',
    '@pinia/nuxt',
    'pinia-plugin-persistedstate/nuxt',
    '@element-plus/nuxt',
    'nuxt-vue3-google-signin'
  ],

  plugins: [
    '~/plugins/fingerprint.ts',
    '~/plugins/keyListener.ts',
    '~/plugins/contextMenu.ts'
  ],

  runtimeConfig: {
    public: {
      apiBase: process.env.NUXT_API_BASE
    }
  },

  build: {
    transpile: ['dayjs', 'element-plus'], // 让 Nuxt 处理 `dayjs`
  },

  elementPlus: { /** Options */ },
  css: ['~/assets/css/styles.css'],
  devtools: { enabled: true },
  tailwindcss: {},

  pinia: {
    storesDirs: ['./stores/**'],
  },

  googleSignIn: {
    clientId: process.env.BOOKMARKIFY_GOOGLE_LOGIN_ID
  },

  compatibilityDate: '2025-02-12',
})