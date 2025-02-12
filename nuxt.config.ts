export default defineNuxtConfig({
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

  compatibilityDate: '2025-02-11',
})