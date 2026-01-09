<template>
  <div
    class="border border-dashed border-gray-300"
    :class="{ 'is-add': isAddItem(item) }"
    :data-is-add-item="isAddItem(item)"
    @contextmenu="onItemContextMenu($event, item)">
    <div v-if="isAddItem(item)">
      <LaunchpadAddOne @success="addBookmark" />
    </div>
    <div v-else>
      <LaunchpadCellFolder
        v-if="item.type === HomeItemType.BOOKMARK_DIR"
        :value="toBookmarkDir(item)"
        :show-title="showTitle"
        @click="openDir(item)" />
      <LaunchpadCellBookmark
        v-else-if="item.type === HomeItemType.BOOKMARK"
        :value="item.typeApp!"
        :show-title="showTitle"
        @click="openPage(item.typeApp!)" />
      <LaunchpadCellBookmarkLoading
        v-else-if="item.type === HomeItemType.BOOKMARK_LOADING"
        :show-title="showTitle" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksDel } from '@api'
import { BookmarkOpenMode, HomeItemType, type BookmarkDir, type BookmarkShow, type UserLayoutNodeVO } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'

type AddItem = { id: string; sort: number; type: 'ADD' }
type GridItem = UserLayoutNodeVO | AddItem

const ADD_ITEM_ID = '__bookmark_add_placeholder__'

const props = defineProps<{
  item: GridItem
  toggleDrag?: boolean
}>()

const emit = defineEmits<{
  (e: 'open-dir', item: UserLayoutNodeVO): void
  (e: 'show-detail', bookmark: BookmarkShow): void
}>()

const bookmarkStore = useBookmarkStore()
const preferenceStore = usePreferenceStore()

const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
const bookmarkOpenMode = computed<BookmarkOpenMode>(
  () => preferenceStore.preference?.bookmarkOpenMode ?? BookmarkOpenMode.CURRENT_TAB,
)

function isAddItem(item: GridItem): item is AddItem {
  return item.id === ADD_ITEM_ID || (item as AddItem).type === 'ADD'
}

function addBookmark(item: UserLayoutNodeVO) {
  if (item.typeApp != null) {
    bookmarkStore.layoutNode.push(item)
  } else {
    bookmarkStore.addEmpty(item)
  }
}

function toBookmarkDir(item: UserLayoutNodeVO): BookmarkDir {
  return {
    name: item.name ?? '文件夹',
    bookmarkList: (item.children ?? [])
      .map((child) => child.typeApp)
      .filter((child): child is BookmarkShow => Boolean(child)),
  }
}

function openDir(item: UserLayoutNodeVO) {
  if (props.toggleDrag) return
  emit('open-dir', item)
}

function openPage(bookmark: BookmarkShow) {
  if (props.toggleDrag) return
  const target = bookmarkOpenMode.value === BookmarkOpenMode.NEW_TAB ? '_blank' : '_self'
  window.open(bookmark.urlFull, target)
}

async function delOne(item: UserLayoutNodeVO) {
  if (props.toggleDrag || isAddItem(item)) return
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
    {
      label: '查看详情',
      onClick: () => emit('show-detail', item.typeApp!),
    },
    {
      label: '删除书签',
      onClick: () => delOne(item),
    },
  ]
}

function onItemContextMenu(e: MouseEvent, item: GridItem) {
  if (props.toggleDrag || isAddItem(item) || !('typeApp' in item) || !item.typeApp) return
  ContextMenu.showContextMenu({ items: getClickMenu(item), x: e.x, y: e.y })
}
</script>