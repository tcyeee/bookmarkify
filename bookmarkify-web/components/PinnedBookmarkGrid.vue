<template>
  <div class="flex flex-wrap gap-4">
    <a
      v-for="node in nodes"
      :key="node.id"
      :href="node.typeApp!.urlFull"
      target="_blank"
      rel="noopener noreferrer"
      class="w-16 flex flex-col items-center gap-1 rounded-lg p-1.5 hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
      @contextmenu.prevent="onContextMenu($event, node)">
      <BookmarkLogo :value="node.typeApp!" :size="56" :prefer-hd="true" />
      <span class="w-full text-xs text-slate-600 dark:text-slate-300 truncate text-center">
        {{ node.typeApp!.title || node.typeApp!.urlBase }}
      </span>
    </a>
  </div>
</template>

<script lang="ts" setup>
import { h } from 'vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { Icon } from '@iconify/vue'
import { bookmarksDel, bookmarksPin } from '@api'
import type { UserLayoutNodeVO } from '@typing'
import BookmarkLogo from '@/components/launchpad/cell/BookmarkLogo.vue'

defineOptions({ name: 'PinnedBookmarkGrid' })

defineProps<{ nodes: UserLayoutNodeVO[] }>()
const emit = defineEmits<{ edit: [node: UserLayoutNodeVO] }>()

const bookmarkStore = useBookmarkStore()

async function unpinOne(node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  try {
    await bookmarksPin(node.typeApp.bookmarkUserLinkId, false)
    bookmarkStore.setPinnedLocal(node.id, false)
    useToastStore().success('已取消置顶')
  } catch (error) {
    console.error('[PinnedBookmarkGrid] 取消置顶失败', error)
  }
}

async function delOne(node: UserLayoutNodeVO) {
  try {
    await useConfirmStore().confirm(`确定删除书签「${node.typeApp?.title || node.typeApp?.urlBase}」吗？`, { type: 'warning' })
  } catch {
    return
  }
  try {
    await bookmarksDel([node.id])
    bookmarkStore.removeNode(node.id)
    useToastStore().success('删除成功')
  } catch (error) {
    console.error('[PinnedBookmarkGrid] 删除书签失败', error)
  }
}

function onContextMenu(e: MouseEvent, node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  ContextMenu.showContextMenu({
    items: [
      { label: '取消置顶', icon: h(Icon, { icon: 'mdi:pin-off', class: 'size-4' }), onClick: () => unpinOne(node) },
      { label: '修改', icon: h(Icon, { icon: 'mdi:pencil', class: 'size-4' }), onClick: () => emit('edit', node) },
      { label: '删除', icon: h(Icon, { icon: 'mdi:trash-can', class: 'size-4' }), onClick: () => delOne(node) },
    ],
    x: e.x,
    y: e.y,
  })
}
</script>
