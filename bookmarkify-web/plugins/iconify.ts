import { addCollection } from '@iconify/vue'
import memoryIcons from '@iconify-json/memory/icons.json'

export default defineNuxtPlugin(() => {
  addCollection(memoryIcons as any)
})
