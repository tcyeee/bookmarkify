import tailwindcss from "@tailwindcss/vite";
export default defineNuxtConfig({
  app: {
    head: {
      title: 'bookmarkify', // 默认标题
    },
  },
  modules: [
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
  css: ['~/assets/css/main.css'],
  devtools: { enabled: true },
  pinia: {
    storesDirs: ['./stores/**'],
  },

  googleSignIn: {
    clientId: process.env.BOOKMARKIFY_GOOGLE_LOGIN_ID
  },

  compatibilityDate: '2025-02-12',
})