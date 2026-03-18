import { addCollection, Icon } from '@iconify/vue'
import memoryIcons from '@iconify-json/memory/icons.json'

export default defineNuxtPlugin((nuxtApp) => {
  addCollection(memoryIcons as any)
  nuxtApp.vueApp.component('Icon', Icon)
})
