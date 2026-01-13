<template>
  <div @contextmenu="onItemContextMenu($event, item)">
    <div>
      <LaunchpadCellFolder v-if="item.type === HomeItemType.BOOKMARK_DIR" :value="item" :toggle-drag="toggleDrag" />
      <LaunchpadCellBookmark v-if="item.type === HomeItemType.BOOKMARK" :value="item.typeApp!" :toggle-drag="toggleDrag" />
      <LaunchpadCellBookmarkLoading v-if="item.type === HomeItemType.BOOKMARK_LOADING" />
      <LaunchpadCellFunction v-if="item.type === HomeItemType.FUNCTION" :value="item.typeFuc!" :toggleDrag="toggleDrag" />
    </div>
  </div>
</template>

<script setup lang="ts">
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksDel } from '@api'
import { HomeItemType, type BookmarkShow, type UserLayoutNodeVO } from '@typing'

const bookmarkStore = useBookmarkStore()
const props = defineProps<{ item: UserLayoutNodeVO; toggleDrag?: boolean }>()
const emit = defineEmits<{
  (e: 'open-dir', item: UserLayoutNodeVO): void
  (e: 'show-detail', bookmark: BookmarkShow): void
}>()

async function delOne(item: UserLayoutNodeVO) {
  if (props.toggleDrag) return
  try {
    await bookmarksDel([item.id])
    const index = bookmarkStore.layoutNode.findIndex((it) => it.id === item.id)
    if (index !== -1) bookmarkStore.layoutNode.splice(index, 1)
  } catch (error) {
    console.error('[LaunchItem] 删除书签失败', error)
  }
}

function getClickMenu(item: UserLayoutNodeVO) {
  if (!item.typeApp) return []
  return [
    { label: '查看详情', onClick: () => emit('show-detail', item.typeApp!) },
    { label: '删除书签', onClick: () => delOne(item) },
  ]
}

function onItemContextMenu(e: MouseEvent, item: UserLayoutNodeVO) {
  if (props.toggleDrag || !('typeApp' in item) || !item.typeApp) return
  ContextMenu.showContextMenu({ items: getClickMenu(item), x: e.x, y: e.y })
}
</script>
