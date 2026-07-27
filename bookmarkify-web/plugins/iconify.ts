import { addCollection, Icon } from '@iconify/vue'
import mdiIcons from '../assets/icons/mdi-subset.json'

export default defineNuxtPlugin((nuxtApp) => {
  addCollection(mdiIcons as any)
  nuxtApp.vueApp.component('Icon', Icon)
})
