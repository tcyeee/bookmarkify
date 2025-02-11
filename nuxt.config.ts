export default defineNuxtConfig({
  modules: [
    '@nuxtjs/tailwindcss',
    '@pinia/nuxt',
    'pinia-plugin-persistedstate/nuxt',
    '@element-plus/nuxt',
    'nuxt-vue3-google-signin'
  ],
  elementPlus: { /** Options */ },
  css: ['~/assets/css/styles.css'],
  plugins: [
    '~/plugins/fingerprint.ts',
    '~/plugins/keyListener.ts',
    '~/plugins/contextMenu.ts'
  ],
  compatibilityDate: '2024-11-01',
  devtools: { enabled: true },
  tailwindcss: {},
  googleSignIn: {
    clientId: process.env.BOOKMARKIFY_GOOGLE_LOGIN_ID
  },
})
