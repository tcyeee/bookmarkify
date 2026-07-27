<template>
  <div>
    <template v-if="node.type === HomeItemType.BOOKMARK_DIR">
      <div class="flex items-center gap-2 py-1.5" :style="indentStyle">
        <Icon icon="memory:folder" class="size-4 text-amber-500 shrink-0" />
        <span class="text-sm font-medium text-slate-500 dark:text-slate-400 truncate">{{ node.name || '文件夹' }}</span>
      </div>
      <BookmarkTreeRow
        v-for="child in children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
        @edit="(n) => emit('edit', n)" />
    </template>

    <a
      v-else-if="node.type === HomeItemType.BOOKMARK && node.typeApp"
      :href="node.typeApp.urlFull"
      target="_blank"
      rel="noopener noreferrer"
      class="flex items-center gap-2 py-1.5 rounded hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
      :style="indentStyle"
      @contextmenu.prevent="onContextMenu($event, node)">
      <BookmarkLogo :value="node.typeApp" :size="20" />
      <span class="text-sm text-slate-700 dark:text-slate-200 truncate">{{ node.typeApp.title || node.typeApp.urlBase }}</span>
    </a>

    <div
      v-else-if="node.type === HomeItemType.BOOKMARK_LOADING"
      class="flex items-center gap-2 py-1.5"
      :style="indentStyle">
      <div class="size-5 rounded-full bg-slate-200 dark:bg-slate-700 animate-pulse shrink-0" />
      <span class="text-sm text-slate-400 dark:text-slate-500 truncate">{{ node.name || '加载中…' }}</span>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksDel } from '@api'
import { HomeItemType, type UserLayoutNodeVO } from '@typing'
import BookmarkLogo from '@/components/launchpad/cell/BookmarkLogo.vue'

defineOptions({ name: 'BookmarkTreeRow' })

const props = defineProps<{ node: UserLayoutNodeVO; depth: number }>()
const emit = defineEmits<{ edit: [node: UserLayoutNodeVO] }>()

const bookmarkStore = useBookmarkStore()
const children = computed(() =>
  props.node.type === HomeItemType.BOOKMARK_DIR ? bookmarkStore.childrenOf(props.node.id) : [],
)
const indentStyle = computed(() => ({ paddingLeft: `${props.depth * 1.25}rem` }))

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
    console.error('[BookmarkTreeRow] 删除书签失败', error)
  }
}

function onContextMenu(e: MouseEvent, node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  ContextMenu.showContextMenu({
    items: [
      { label: '修改', onClick: () => emit('edit', node) },
      { label: '删除', onClick: () => delOne(node) },
    ],
    x: e.x,
    y: e.y,
  })
}
</script>
