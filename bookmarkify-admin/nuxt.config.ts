// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  modules: [
    '@nuxt/eslint',
    '@nuxt/ui',
    '@element-plus/nuxt'
  ],

  devtools: {
    enabled: true
  },

  css: ['~/assets/css/main.css'],

  routeRules: {
    // '/': { prerender: true }
  },

  future: {
    compatibilityVersion: 4
  },

  devServer: {
    port: 3010
  },

  compatibilityDate: '2025-01-15',

  vite: {
    server: {
      hmr: {
        port: 3020
      }
    }
  },

  eslint: {
    config: {
      stylistic: {
        commaDangle: 'never',
        braceStyle: '1tbs'
      }
    }
  }
})
