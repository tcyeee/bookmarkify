import { createSSRApp, h } from 'vue'
import { renderToString } from './node_modules/.pnpm/@vue+server-renderer@3.5.40/node_modules/@vue/server-renderer/dist/server-renderer.esm-bundler.js'
import { addCollection, Icon } from '@iconify/vue'
import memoryIcons from '@iconify-json/memory/icons.json' with { type: 'json' }
import { ContextMenuItem } from '@imengyu/vue3-context-menu'

addCollection(memoryIcons)

// Mirror exactly what pages/index.vue does when opening the context menu:
// icon: h(Icon, { icon: 'memory:pencil', class: 'size-4' })
const app = createSSRApp({
  render() {
    return h(ContextMenuItem, {
      label: '修改',
      icon: h(Icon, { icon: 'memory:pencil', class: 'size-4' }),
    })
  },
})

const html = await renderToString(app)
console.log('--- rendered HTML ---')
console.log(html)
console.log('--- contains <svg> ---')
console.log(html.includes('<svg'))
