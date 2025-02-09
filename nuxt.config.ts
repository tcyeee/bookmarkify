export default defineNuxtConfig({
  modules: [
    '@nuxtjs/tailwindcss',
    '@pinia/nuxt',
    'pinia-plugin-persistedstate/nuxt',
    '@element-plus/nuxt'
  ],
  elementPlus: { /** Options */ },
  css: ['~/assets/css/styles.css'],
  plugins: ['~/plugins/contextMenu.js'],
  compatibilityDate: '2024-11-01',
  devtools: { enabled: true },
  tailwindcss: {}
})
