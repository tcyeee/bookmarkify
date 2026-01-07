<template>
  <div class="bookmark-grid-wrapper" :style="gridStyle">
    <!-- 一级菜单 -->
    <ClientOnly>
      <Vuuri
        :key="bookmarkLayoutKey"
        v-show="!data.subItemId"
        class="bookmark-grid"
        :model-value="gridItems"
        item-key="id"
        :options="vuuriOptions"
        drag-handle=".bookmark-drag-handle"
        :drag-enabled="true"
        :get-item-width="getItemWidth"
        :get-item-height="getItemHeight"
        @input="onGridInput"
      @dragStart="onDragStart"
        @dragReleaseEnd="onDragReleaseEnd">
        <template #item="{ item }">
          <div
            class="bookmark-item"
            :class="{ 'is-add': isAddItem(item) }"
            :data-is-add-item="isAddItem(item)"
            @contextmenu="onItemContextMenu($event, item)">
            <div v-if="isAddItem(item)" class="bookmark-add-placeholder">
              <LaunchpadAddOne @success="addBookmark" />
            </div>
            <div v-else class="bookmark-drag-handle">
              <LaunchpadCellFolder
                v-if="item.type === HomeItemType.BOOKMARK_DIR"
                :value="toBookmarkDir(item)"
                :show-title="showTitle"
                @click="openDir(item)" />
              <LaunchpadCellBookmark
                v-else-if="item.type === HomeItemType.BOOKMARK"
                :value="item.typeApp"
                :show-title="showTitle"
                @click="openPage(item.typeApp)" />
              <LaunchpadCellBookmarkLoading
                v-else-if="item.type === HomeItemType.BOOKMARK_LOADING"
                :show-title="showTitle" />
            </div>
          </div>
        </template>
      </Vuuri>
    </ClientOnly>

    <!-- 二级菜单 -->
    <div v-if="data.subItemId" class="bookmark-sub-grid">
      <div v-for="bookmark in data.subApps" :key="bookmark.bookmarkId" class="bookmark-item">
        <LaunchpadCellBookmark :value="bookmark" :show-title="showTitle" @click="openPage(bookmark)" />
      </div>
    </div>

    <!-- 书签详情 -->
    <el-dialog v-model="data.bookmarkDetailDialog" class="bookmark-dialog-box" :show-close="false" width="600" top="25vh">
      <LaunchpadDetail v-if="data.bookmarkDetail" :data="data.bookmarkDetail" />
    </el-dialog>
  </div>
</template>
<script lang="ts" setup>
import { defineAsyncComponent, defineComponent } from 'vue'
import ContextMenu from '@imengyu/vue3-context-menu'
import { bookmarksSort, bookmarksDel } from '@api'
import {
  BookmarkLayoutMode,
  BookmarkOpenMode,
  HomeItemType,
  type BookmarkShow,
  type BookmarkDir,
  type UserLayoutNodeVO,
} from '@typing'
import { usePreferenceStore } from '@stores/preference.store'

const sysStore = useSysStore()
const bookmarkStore = useBookmarkStore()
const preferenceStore = usePreferenceStore()

const Vuuri = process.client
  ? defineAsyncComponent(() => import('vuuri'))
  : defineComponent({
      name: 'VuuriPlaceholder',
      setup: () => () => null,
    })

const pageData = computed<Array<UserLayoutNodeVO>>(() => bookmarkStore.layoutNode || [])
const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
const bookmarkOpenMode = computed<BookmarkOpenMode>(
  () => preferenceStore.preference?.bookmarkOpenMode ?? BookmarkOpenMode.CURRENT_TAB,
)

type AddItem = { id: string; sort: number; type: 'ADD' }
type GridItem = UserLayoutNodeVO | AddItem

const ADD_ITEM_ID = '__bookmark_add_placeholder__'

const layoutConfig = computed(() => {
  const layout = preferenceStore.preference?.bookmarkLayout ?? BookmarkLayoutMode.DEFAULT
  switch (layout) {
    case BookmarkLayoutMode.COMPACT:
      return { gap: 1.5, size: 4.25 }
    case BookmarkLayoutMode.SPACIOUS:
      return { gap: 4, size: 6 }
    case BookmarkLayoutMode.DEFAULT:
    default:
      return { gap: 3, size: 5 }
  }
})

const bookmarkLayoutKey = computed(() => preferenceStore.preference?.bookmarkLayout ?? BookmarkLayoutMode.DEFAULT)
const gridGap = computed<number>(() => layoutConfig.value.gap)
const bookmarkGapRem = computed<string>(() => `${gridGap.value}rem`)
const bookmarkSize = computed<string>(() => `${layoutConfig.value.size}rem`)
const bookmarkCellWidth = computed<string>(() => `calc(${bookmarkSize.value} + ${bookmarkGapRem.value})`)

const gridStyle = computed(() => ({
  '--bookmark-gap': `${gridGap.value}rem`,
  '--bookmark-size': bookmarkSize.value,
}))

const addButtonItem = computed<AddItem>(() => ({
  id: ADD_ITEM_ID,
  sort: Number.MAX_SAFE_INTEGER,
  type: 'ADD',
}))

const gridItems = computed<GridItem[]>(() => [...(pageData.value ?? []), addButtonItem.value])

function isAddElement(element?: Element | null) {
  return element?.getAttribute('data-is-add-item') === 'true'
}

function isDragHandleEvent(event: any) {
  const originalEvent = (event?.srcEvent || event?.event || event) as Event | undefined
  const target = originalEvent?.target as HTMLElement | null
  return Boolean(target?.closest('.bookmark-drag-handle'))
}

const vuuriOptions = {
  layout: { fillGaps: true, rounding: false },
  layoutDuration: 250,
  showDuration: 0,
  hideDuration: 0,
  dragReleaseDuration: 50,
  dragStartPredicate: (item: any, event: any) => {
    const element = item?.getElement?.() as HTMLElement | undefined
    if (isAddElement(element)) return false
    return isDragHandleEvent(event)
  },
}

const data = reactive<{
  subApps?: Array<BookmarkShow>
  subItemId?: string
  bookmarkDetailDialog: boolean
  bookmarkDetail?: BookmarkShow
}>({
  bookmarkDetailDialog: false,
})

const dragState = reactive({
  dragging: false,
  justDropped: false,
  originalOrder: undefined as string[] | undefined,
})

watchEffect(() => {
  sysStore.preventKeyEventsFlag = data.bookmarkDetailDialog
})

function addBookmark(item: UserLayoutNodeVO) {
  if (item.typeApp != null) {
    bookmarkStore.layoutNode.push(item)
  } else {
    bookmarkStore.addEmpty(item)
  }
}

function openDir(item: UserLayoutNodeVO) {
  if (dragState.dragging || dragState.justDropped) return
  data.subItemId = item.id
  data.subApps = (item.children ?? [])
    .map((child) => child.typeApp)
    .filter((child): child is BookmarkShow => Boolean(child))
  window.scrollTo({
    top: 0,
    behavior: 'smooth', // 平滑滚动到顶部，可选
  })
}

function toBookmarkDir(item: UserLayoutNodeVO): BookmarkDir {
  return {
    name: item.name ?? '文件夹',
    bookmarkList: (item.children ?? [])
      .map((child) => child.typeApp)
      .filter((child): child is BookmarkShow => Boolean(child)),
  }
}

function openPage(bookmark: BookmarkShow) {
  if (dragState.dragging || dragState.justDropped) return
  const target = bookmarkOpenMode.value === BookmarkOpenMode.NEW_TAB ? '_blank' : '_self'
  window.open(bookmark.urlFull, target)
}

function getClickMenu(item: UserLayoutNodeVO) {
  if (!item.typeApp) return []
  return [
    {
      label: '查看详情',
      onClick: () => clickDetail(item),
    },
    {
      label: '删除书签',
      onClick: () => delOne(item),
    },
  ]
}

// 查看详情
function clickDetail(item: UserLayoutNodeVO) {
  if (!item.typeApp) return
  data.bookmarkDetailDialog = true
  data.bookmarkDetail = item.typeApp
}

// 删除书签
function delOne(item: UserLayoutNodeVO) {
  if (!item.typeApp) return
  bookmarksDel([item.id])

  const index: number = pageData.value?.findIndex((a) => a.id == item.id) || -1
  if (index !== -1) pageData.value?.splice(index, 1)
}

function isAddItem(item: GridItem): item is AddItem {
  return item.id === ADD_ITEM_ID
}

function onItemContextMenu(e: MouseEvent, item: GridItem) {
  if (isAddItem(item)) return
  ContextMenu.showContextMenu({ items: getClickMenu(item), x: e.x, y: e.y })
}

function getItemWidth() {
  return bookmarkCellWidth.value
}

function getItemHeight(item: GridItem) {
  const titleSpace = showTitle.value ? '1.6rem' : '0rem'
  return isAddItem(item)
    ? bookmarkCellWidth.value
    : `calc(${bookmarkSize.value} + ${titleSpace} + ${bookmarkGapRem.value})`
}

function onGridInput(items: GridItem[]) {
  bookmarkStore.layoutNode = items.filter((it): it is UserLayoutNodeVO => !isAddItem(it))
}

function onDragStart() {
  dragState.dragging = true
  dragState.justDropped = false
  dragState.originalOrder = bookmarkStore.layoutNode?.map((item) => item.id)
}

function onDragReleaseEnd() {
  dragState.dragging = false
  dragState.justDropped = true
  if (typeof requestAnimationFrame !== 'undefined') {
    requestAnimationFrame(() => {
      dragState.justDropped = false
    })
  } else {
    setTimeout(() => (dragState.justDropped = false), 16)
  }
  sort()
}

// 重新排序
function sort() {
  const currentOrder = bookmarkStore.layoutNode?.map((item) => item.id) ?? []
  const previousOrder = dragState.originalOrder
  dragState.originalOrder = undefined

  const changed =
    !previousOrder ||
    previousOrder.length !== currentOrder.length ||
    previousOrder.some((id, idx) => id !== currentOrder[idx])

  if (!changed) return

  const params: Record<string, number> = {}
  bookmarkStore.layoutNode?.forEach((item, index) => {
    params[item.id] = index
  })
  bookmarksSort(params)
}
</script>

<style>
.bookmark-grid-wrapper {
  width: 100%;
  display: flex;
  justify-content: center;
  box-sizing: border-box;
  padding-inline: calc(var(--bookmark-gap) / 2);
  --bookmark-gap: 3rem;
  --bookmark-size: 5rem;
}

.bookmark-grid {
  width: 100%;
  min-height: calc(var(--bookmark-size) + 1.6rem + var(--bookmark-gap));
}

.bookmark-grid .muuri-item {
  margin: 0;
}

.bookmark-grid .muuri-item-content {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  box-sizing: border-box;
}

.bookmark-grid .bookmark-item {
  width: var(--bookmark-size);
}

.bookmark-drag-handle {
  cursor: grab;
}

.bookmark-add-placeholder {
  width: var(--bookmark-size);
  height: var(--bookmark-size);
  display: flex;
  align-items: center;
  justify-content: center;
}

.bookmark-sub-grid {
  display: flex;
  flex-wrap: wrap;
  gap: var(--bookmark-gap);
}

.bookmark-sub-grid .bookmark-item {
  width: var(--bookmark-size);
}

.bookmark-dialog-box {
  border-radius: 2rem;
  padding: 3rem;
}
</style>
