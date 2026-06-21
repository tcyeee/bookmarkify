<template>
  <div class="h-full w-full" @contextmenu="onContextMenu($event, item)">
    <LaunchpadCellFolder v-if="item.type === HomeItemType.BOOKMARK_DIR" :value="item" :toggle-drag="dragging" @open-dir="emit('open-dir', item)" />
    <LaunchpadCellBookmark v-else-if="item.type === HomeItemType.BOOKMARK || item.type === HomeItemType.BOOKMARK_LOADING" :value="item.typeApp" :temp-title="item.name ?? undefined" :toggle-drag="dragging" :node-id="item.id" />
    <LaunchpadCellFunction v-else-if="item.type === HomeItemType.FUNCTION" :value="item.typeFuc!" :toggle-drag="dragging" />
  </div>
</template>

<script setup lang="ts">
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksDel } from '@api'
import { HomeItemType, type BookmarkShow, type UserLayoutNodeVO } from '@typing'

const bookmarkStore = useBookmarkStore()
const props = defineProps<{ item: UserLayoutNodeVO; dragging?: boolean }>()
const emit = defineEmits<{ (e: 'open-dir', item: UserLayoutNodeVO): void; (e: 'show-detail', bookmark: BookmarkShow): void }>()

async function delOne(item: UserLayoutNodeVO) {
  if (props.dragging) return
  try {
    await bookmarksDel([item.id])
    bookmarkStore.removeNode(item.id)
  } catch (error) {
    console.error('[LaunchCell] 删除书签失败', error)
  }
}

function onContextMenu(e: MouseEvent, item: UserLayoutNodeVO) {
  if (props.dragging || !item.typeApp) return
  ContextMenu.showContextMenu({
    items: [
      { label: '查看详情', onClick: () => emit('show-detail', item.typeApp!) },
      { label: '删除书签', onClick: () => delOne(item) },
    ],
    x: e.x,
    y: e.y,
  })
}
</script>
